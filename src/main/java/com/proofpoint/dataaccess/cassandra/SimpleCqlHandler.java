package com.proofpoint.dataaccess.cassandra;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.proofpoint.concurrent.Threads;
import com.proofpoint.dataaccess.cassandra.SimpleCqlFactory.ConfiguredExecution;
import com.proofpoint.log.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class SimpleCqlHandler<T extends SimpleCqlMapper> implements InvocationHandler
{
    private static final Logger logger = Logger.get(SimpleCqlHandler.class);
    static final Method TO_STRING;
    static final Method EQUALS;
    static final Method HASH_CODE;
    static final Method PRETTY_PRINT;

    private static long clockSkewPad = 1 * 1000; /* additional safeguard for clock skew */

    private final Object[] paramValues;
    private final SimpleCqlFactory<T> mapper;
    private final ConfiguredExecution<T> executionConfig;
    private ConsistencyLevel consistencyLevel = null;
    private ConsistencyLevel serialConsistencyLevel = null;

    static Executor executor = Executors.newCachedThreadPool(Threads.daemonThreadsNamed("cql-handler-%d"));

    private static Map<String, Function<SimpleCqlHandler, Object>> executeMethods = new HashMap<>();
    private static Map<String, BiConsumer<SimpleCqlHandler, Object>> oneArgMethods = new HashMap<>();

    static {
        try {
            TO_STRING = Object.class.getMethod("toString");
            EQUALS = Object.class.getMethod("equals", Object.class);
            HASH_CODE = Object.class.getMethod("hashCode");
            PRETTY_PRINT = SimpleCqlPrettyPrintable.class.getMethod("prettyPrint", BiFunction.class);
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError(e); // Can't happen
        }
        executeMethods.put("getParamValues", SimpleCqlHandler::getParamValues);
        executeMethods.put("execute", SimpleCqlHandler::execute);
        executeMethods.put("bind", SimpleCqlHandler::bind);
        executeMethods.put("executeAsync", SimpleCqlHandler::executeAsync);
        executeMethods.put("executeAsyncAndMap", SimpleCqlHandler::executeAsyncAndMap);
        executeMethods.put("executeAsyncAndMapOne", SimpleCqlHandler::executeAsyncAndMapOne);
        executeMethods.put("executeAsyncAndMapAtMostOne", SimpleCqlHandler::executeAsyncAndMapAtMostOne);

        oneArgMethods.put("withConsistencyLevel", SimpleCqlHandler::withConsistencyLevel);
        oneArgMethods.put("withSerialConsistencyLevel", SimpleCqlHandler::withSerialConsistencyLevel);
    }


    SimpleCqlHandler(ConfiguredExecution<T> factoryWrapper)
    {
        this.mapper = factoryWrapper.getFactory();
        this.executionConfig = factoryWrapper;

        paramValues = new Object[mapper.queryParamBindingMethods.size()];
        Arrays.fill(paramValues, null);
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args)
            throws Throwable
    {
        if (args == null || args.length == 0) {
            Function<SimpleCqlHandler, Object> instanceMethod = executeMethods.get(m.getName());
            if (instanceMethod != null) {
                return instanceMethod.apply(this);
            }
            if (m.equals(TO_STRING)) {
                return mapper.clazz.getSimpleName() + " implementation generated by the SimpleCqlMapper";
            }
            if (m.equals(HASH_CODE)) {
                return System.identityHashCode(proxy);
            }
            if (m.getName().equals("getParamValues")) {
                return getParamValues();
            }
            throw new RuntimeException(String.format("SimpleCqlMapper cannot invoke no-args method %s", m.toString()));
        }
        else {
            Integer index = mapper.queryParamBindingMethods.get(m);
            if (index != null) {
                paramValues[index] = SimpleCqlFactory.mapValue(args[0]);
                return proxy;
            }
            if (m.equals(EQUALS)) {
                return proxy == args[0];
            }
            BiConsumer<SimpleCqlHandler, Object> instanceMethod = oneArgMethods.get(m.getName());
            if (instanceMethod != null) {
                instanceMethod.accept(this, args[0]);
                return proxy;
            }
            throw new RuntimeException(String.format("SimpleCqlMapper cannot invoke %s with %d args", m.toString(), args.length));
        }
    }

    public ResultSet execute()
    {
        Session session = mapper.connector.getSessionWithTimeout();
        if (mapper.getNumRotations() == 1) {
            return execute(0, session);
        }
        else if (executionConfig.isSingleTableExecution()) {
            return execute(executionConfig.getTid(), session);
        }
        else if (mapper.isUpsert()) {
            CassandraRotationInfo rotationInfo = mapper.getRotationInfo();
            return execute(rotationInfo.getCurrTableIndex(), session);
        }
        throw new RuntimeException("SimpleCqlMapper does not support merging ResultSets. Please use execAsyncAndMap* executeMethods.");
    }

    public CompletableFuture<ResultSet> executeAsync()
    {
        Session session = mapper.connector.getSessionWithTimeout();
        if (mapper.getNumRotations() == 1) {
            return executeAsync(0, session);
        }
        else if (executionConfig.isSingleTableExecution()) {
            return executeAsync(executionConfig.getTid(), session);
        }
        else if (mapper.isUpsert()) {
            CassandraRotationInfo rotationInfo = mapper.getRotationInfo();
            return executeAsync(rotationInfo.getCurrTableIndex(), session);
        }
        throw new RuntimeException("SimpleCqlMapper does not support merging ResultSets. Please use execAsyncAndMap* executeMethods.");
    }

    int figureTidRange(CassandraRotationInfo rotationInfo)
    {
        int tidRange = executionConfig.useRangeTid() ?
                executionConfig.getTidRange() :
                executionConfig.isOptimal() ? 2 : 0;
        if (tidRange == 0) {
            throw new RuntimeException("TID range must be set");
        }
        // TODO: support use case when expiration period is N*rotationPeriod
        if (executionConfig.useExpiration() && (rotationInfo.getTimeElapsedInCurrent() >= (mapper.getExpirationPeriod() + clockSkewPad))) {
            tidRange--;
        }
        return tidRange;
    }

    public CompletableFuture<Collection<T>> executeAsyncAndMap()
    {
        Session session = mapper.connector.getSessionWithTimeout();
        if (mapper.getNumRotations() == 1) {
            return executeAsync(0, session).thenApply(mapper::mapCollection);
        }
        else if (executionConfig.isSingleTableExecution()) {
            return executeAsync(executionConfig.getTid(), session).thenApply(mapper::mapCollection);
        }
        CassandraRotationInfo rotationInfo = mapper.getRotationInfo();
        int tidRange = figureTidRange(rotationInfo);
        if (logger.isDebugEnabled()) {
            logger.debug("\"%s\" fetch from tables {%s}", mapper.getQuery(), rotationInfo.getQueryTids().stream().limit(tidRange).map(i -> i.toString()).collect(Collectors.joining(",")));
        }
        return new CompletableFuture<>().supplyAsync(() -> mapDriverFuturesToObjects(session, rotationInfo.getQueryTids(), tidRange).collect(Collectors.toList()), SimpleCqlHandler.executor);
    }

    public CompletableFuture<Optional<?>> executeAsyncAndMapOne()
    {
        Session session = mapper.connector.getSessionWithTimeout();
        if (mapper.getNumRotations() == 1) {
            return executeAsync(0, session).thenApply(mapper::mapOneOptionally);
        }
        else if (executionConfig.isSingleTableExecution()) {
            return executeAsync(executionConfig.getTid(), session).thenApply(mapper::mapOneOptionally);
        }
        CassandraRotationInfo rotationInfo = mapper.getRotationInfo();
        int tidRange = figureTidRange(rotationInfo);
        if (logger.isDebugEnabled()) {
            logger.debug("\"%s\" fetch from tables {%s}", mapper.getQuery(), rotationInfo.getQueryTids().stream().limit(tidRange).map(i -> i.toString()).collect(Collectors.joining(",")));
        }
        if (executionConfig.isParallel(false)) {
            return new CompletableFuture<>().supplyAsync(() -> mapDriverFuturesToObjects(session, rotationInfo.getQueryTids(), tidRange).findFirst(), SimpleCqlHandler.executor);
        }
        else {
            return new CompletableFuture<>().supplyAsync(() -> mapDriverFuturesToOptional(session, rotationInfo.getQueryTids(), tidRange), SimpleCqlHandler.executor);
        }
    }

    public CompletableFuture<Optional<?>> executeAsyncAndMapAtMostOne()
    {
        Session session = mapper.connector.getSessionWithTimeout();
        if (mapper.getNumRotations() == 1) {
            return executeAsync(0, session).thenApply(mapper::mapOneOptionally);
        }
        else if (executionConfig.isSingleTableExecution()) {
            return executeAsync(executionConfig.getTid(), session).thenApply(mapper::mapOneOptionally);
        }
        CassandraRotationInfo rotationInfo = mapper.getRotationInfo();
        int tidRange = figureTidRange(rotationInfo);
        if (logger.isDebugEnabled()) {
            logger.debug("\"%s\" fetch from tables {%s}", mapper.getQuery(), rotationInfo.getQueryTids().stream().limit(tidRange).map(i -> i.toString()).collect(Collectors.joining(",")));
        }
        return new CompletableFuture<>()
                .supplyAsync(() -> mapDriverFuturesToObjects(session, rotationInfo.getQueryTids(), tidRange), SimpleCqlHandler.executor)
                .thenApply(coll -> {
                            if (coll.count() <= 1) {
                                return coll.findAny();
                            }
                            throw new RuntimeException("SimpleCqlMapper: Too many results from " + mapper.getQuery());
                        }
                );
    }

    private Optional<T> mapDriverFuturesToOptional(Session session, List<Integer> tids, int limit)
    {
        for (int i = 0; i < limit; i++) {
            int tid = tids.get(i);
            ResultSetFuture future = session.executeAsync(bind(tid));
            Optional<T> result = mapper.mapOneOptionally(future.getUninterruptibly());
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }


    private Stream<T> mapDriverFuturesToObjects(Session session, List<Integer> tids, int limit)
    {
        // Fire off all queries, get Datastax futures
        List<ResultSetFuture> futures = tids.stream().limit(limit).map(tid -> session.executeAsync(bind(tid))).collect(Collectors.toList());
        // join all futures and map to objects
        return mapDriverFuturesToObjects(futures);
    }

    private Stream<T> mapDriverFuturesToObjects(List<ResultSetFuture> futures)
    {
        // TODO: use the timeout here?
        return futures.stream().flatMap(future -> mapper.<T>mapCollection(future.getUninterruptibly()).stream());
    }

    public Object[] getParamValues()
    {
        return Arrays.copyOf(paramValues, paramValues.length);
    }

    private void withConsistencyLevel(Object consistencyLevel)
    {
        this.consistencyLevel = (ConsistencyLevel) consistencyLevel;
    }

    private void withSerialConsistencyLevel(Object consistencyLevel)
    {
        this.serialConsistencyLevel = (ConsistencyLevel) consistencyLevel;
    }

    public BoundStatement bind()
    {
        int tid = executionConfig.getTid();
        BoundStatement boundStatement = mapper.preparedStatement[(tid == -1) ? 0 : tid].bind(paramValues);
        if (consistencyLevel != null) {
            boundStatement.setConsistencyLevel(consistencyLevel);
        }
        if (serialConsistencyLevel != null) {
            boundStatement.setSerialConsistencyLevel(serialConsistencyLevel);
        }
        return boundStatement;
    }

    private BoundStatement bind(int tid)
    {
        return mapper.preparedStatement[tid].bind(paramValues);
    }

    private ResultSet execute(int tid, Session session)
    {
        if (logger.isDebugEnabled()) {
            logger.debug("\"%s\" query from single table {%d}", mapper.getQuery(), tid);
        }
        return session.execute(bind(tid));
    }

    private CompletableFuture<ResultSet> executeAsync(int tid, Session session)
    {
        if (logger.isDebugEnabled()) {
            logger.debug("\"%s\" query from single table {%d}", mapper.getQuery(), tid);
        }
        ResultSetFuture future = session.executeAsync(bind(tid));

        // NOTE: using supplyAsync with executor vs. the simple Futures.addCallback(lf, new FutureCallback<T>() {...} here because
        // the DAO user code will call thenApply(mapper::mapOneOptionally) on the result set, on the supplying thread (which is the cassandra driver thread) which may block.
        // We don't want to perform mapping on the datastax thread, hence the executor. Perhaps this can be done mre optimal by only spawning a thread
        // when dataset is exhausted, and not spawning when expecting at most one result in the first place.
        CompletableFuture<ResultSet> completableFuture = new CompletableFuture<>().supplyAsync(() -> future.getUninterruptibly(), SimpleCqlHandler.executor);
        return completableFuture;
    }
}