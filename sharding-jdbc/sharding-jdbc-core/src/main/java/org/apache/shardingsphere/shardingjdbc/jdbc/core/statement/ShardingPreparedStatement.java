/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.shardingjdbc.jdbc.core.statement;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import lombok.Getter;
import org.apache.shardingsphere.core.merge.MergeEntry;
import org.apache.shardingsphere.core.route.SQLRouteResult;
import org.apache.shardingsphere.core.route.router.sharding.keygen.GeneratedKey;
import org.apache.shardingsphere.core.shard.PreparedQueryShardingEngine;
import org.apache.shardingsphere.sharding.execute.sql.execute.result.StreamQueryResult;
import org.apache.shardingsphere.shardingjdbc.executor.BatchPreparedStatementExecutor;
import org.apache.shardingsphere.shardingjdbc.executor.PreparedStatementExecutor;
import org.apache.shardingsphere.shardingjdbc.jdbc.adapter.AbstractShardingPreparedStatementAdapter;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.connection.ShardingConnection;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.constant.SQLExceptionConstant;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.context.ShardingRuntimeContext;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.resultset.GeneratedKeysResultSet;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.resultset.ShardingResultSet;
import org.apache.shardingsphere.shardingjdbc.merge.JDBCMergeEntry;
import org.apache.shardingsphere.sql.parser.relation.statement.impl.SelectSQLStatementContext;
import org.apache.shardingsphere.sql.parser.sql.statement.dal.DALStatement;
import org.apache.shardingsphere.sql.parser.sql.statement.dml.InsertStatement;
import org.apache.shardingsphere.underlying.common.constant.properties.PropertiesConstant;
import org.apache.shardingsphere.underlying.common.rule.BaseRule;
import org.apache.shardingsphere.underlying.executor.QueryResult;
import org.apache.shardingsphere.underlying.merge.result.MergedResult;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * PreparedStatement that support sharding.
 *
 * @author zhangliang
 * @author caohao
 * @author maxiaoguang
 * @author panjuan
 */
public final class ShardingPreparedStatement extends AbstractShardingPreparedStatementAdapter {
    
    @Getter
    private final ShardingConnection connection;
    
    private final String sql;
    
    private final PreparedQueryShardingEngine shardingEngine;
    
    private final PreparedStatementExecutor preparedStatementExecutor;
    
    private final BatchPreparedStatementExecutor batchPreparedStatementExecutor;
    
    private SQLRouteResult routeResult;
    
    private ResultSet currentResultSet;
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql) throws SQLException {
        this(connection, sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT, false);
    }
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency) throws SQLException {
        this(connection, sql, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT, false);
    }
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql, final int autoGeneratedKeys) throws SQLException {
        this(connection, sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT, Statement.RETURN_GENERATED_KEYS == autoGeneratedKeys);
    }
    
    public ShardingPreparedStatement(
            final ShardingConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) throws SQLException {
        this(connection, sql, resultSetType, resultSetConcurrency, resultSetHoldability, false);
    }
    
    private ShardingPreparedStatement(
            final ShardingConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability, final boolean returnGeneratedKeys)
            throws SQLException {
        if (Strings.isNullOrEmpty(sql)) {
            throw new SQLException(SQLExceptionConstant.SQL_STRING_NULL_OR_EMPTY);
        }
        this.connection = connection;
        this.sql = sql;
        ShardingRuntimeContext runtimeContext = connection.getRuntimeContext();
        shardingEngine = new PreparedQueryShardingEngine(sql, runtimeContext.getRule(), runtimeContext.getProperties(), runtimeContext.getMetaData(), runtimeContext.getParseEngine());
        preparedStatementExecutor = new PreparedStatementExecutor(resultSetType, resultSetConcurrency, resultSetHoldability, returnGeneratedKeys, connection);
        batchPreparedStatementExecutor = new BatchPreparedStatementExecutor(resultSetType, resultSetConcurrency, resultSetHoldability, returnGeneratedKeys, connection);
    }
    
    @Override
    public ResultSet executeQuery() throws SQLException {
        ResultSet result;
        try {
            clearPrevious();
            shard();
            initPreparedStatementExecutor();
            result = getResultSet(preparedStatementExecutor.executeQuery());
        } finally {
            clearBatch();
        }
        currentResultSet = result;
        return result;
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        if (null != currentResultSet) {
            return currentResultSet;
        }
        List<ResultSet> resultSets = new ArrayList<>(preparedStatementExecutor.getStatements().size());
        List<QueryResult> queryResults = new ArrayList<>(preparedStatementExecutor.getStatements().size());
        for (Statement each : preparedStatementExecutor.getStatements()) {
            ResultSet resultSet = each.getResultSet();
            resultSets.add(resultSet);
            if (null != resultSet) {
                queryResults.add(new StreamQueryResult(resultSet));
            }
        }
        if (routeResult.getSqlStatementContext() instanceof SelectSQLStatementContext || routeResult.getSqlStatementContext().getSqlStatement() instanceof DALStatement) {
            currentResultSet = new ShardingResultSet(resultSets, createMergedResult(resultSets, queryResults), this, routeResult);
            
        }
        return currentResultSet;
    }
    
    private ShardingResultSet getResultSet(final List<QueryResult> queryResults) throws SQLException {
        List<ResultSet> resultSets = preparedStatementExecutor.getResultSets();
        return new ShardingResultSet(resultSets, createMergedResult(resultSets, queryResults), this, routeResult);
    }
    
    private MergedResult createMergedResult(final List<ResultSet> resultSets, final List<QueryResult> queryResults) throws SQLException {
        Collection<BaseRule> rules = Arrays.asList(connection.getRuntimeContext().getRule(), connection.getRuntimeContext().getRule().getEncryptRule());
        boolean queryWithCipherColumn = connection.getRuntimeContext().getProperties().getValue(PropertiesConstant.QUERY_WITH_CIPHER_COLUMN);
        MergeEntry mergeEntry = new JDBCMergeEntry(connection.getRuntimeContext().getDatabaseType(), 
                connection.getRuntimeContext().getMetaData().getRelationMetas(), rules, queryWithCipherColumn, resultSets.get(0).getMetaData());
        return mergeEntry.getMergedResult(queryResults, routeResult.getSqlStatementContext());
    }
    
    @Override
    public int executeUpdate() throws SQLException {
        try {
            clearPrevious();
            shard();
            initPreparedStatementExecutor();
            return preparedStatementExecutor.executeUpdate();
        } finally {
            clearBatch();
        }
    }
    
    @Override
    public boolean execute() throws SQLException {
        try {
            clearPrevious();
            shard();
            initPreparedStatementExecutor();
            return preparedStatementExecutor.execute();
        } finally {
            clearBatch();
        }
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        Optional<GeneratedKey> generatedKey = getGeneratedKey();
        if (preparedStatementExecutor.isReturnGeneratedKeys() && generatedKey.isPresent()) {
            Preconditions.checkState(routeResult.getGeneratedKey().isPresent());
            return new GeneratedKeysResultSet(routeResult.getGeneratedKey().get().getGeneratedValues().iterator(), generatedKey.get().getColumnName(), this);
        }
        if (1 == preparedStatementExecutor.getStatements().size()) {
            return preparedStatementExecutor.getStatements().iterator().next().getGeneratedKeys();
        }
        return new GeneratedKeysResultSet();
    }
    
    private Optional<GeneratedKey> getGeneratedKey() {
        return null != routeResult && routeResult.getSqlStatementContext().getSqlStatement() instanceof InsertStatement ? routeResult.getGeneratedKey() : Optional.<GeneratedKey>absent();
    }
    
    private void initPreparedStatementExecutor() throws SQLException {
        preparedStatementExecutor.init(routeResult);
        setParametersForStatements();
        replayMethodForStatements();
    }
    
    private void setParametersForStatements() {
        for (int i = 0; i < preparedStatementExecutor.getStatements().size(); i++) {
            replaySetParameter((PreparedStatement) preparedStatementExecutor.getStatements().get(i), preparedStatementExecutor.getParameterSets().get(i));
        }
    }
    
    private void replayMethodForStatements() {
        for (Statement each : preparedStatementExecutor.getStatements()) {
            replayMethodsInvocation(each);
        }
    }
    
    private void clearPrevious() throws SQLException {
        preparedStatementExecutor.clear();
    }
    
    @Override
    public void addBatch() {
        try {
            shard();
            batchPreparedStatementExecutor.addBatchForRouteUnits(routeResult);
        } finally {
            currentResultSet = null;
            clearParameters();
        }
    }
    
    private void shard() {
        routeResult = shardingEngine.shard(sql, getParameters());
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        try {
            initBatchPreparedStatementExecutor();
            return batchPreparedStatementExecutor.executeBatch();
        } finally {
            clearBatch();
        }
    }
    
    private void initBatchPreparedStatementExecutor() throws SQLException {
        batchPreparedStatementExecutor.init(routeResult);
        setBatchParametersForStatements();
    }
    
    private void setBatchParametersForStatements() throws SQLException {
        for (Statement each : batchPreparedStatementExecutor.getStatements()) {
            List<List<Object>> parameterSet = batchPreparedStatementExecutor.getParameterSet(each);
            for (List<Object> parameters : parameterSet) {
                replaySetParameter((PreparedStatement) each, parameters);
                ((PreparedStatement) each).addBatch();
            }
        }
    }
    
    @Override
    public void clearBatch() throws SQLException {
        currentResultSet = null;
        batchPreparedStatementExecutor.clear();
        clearParameters();
    }
    
    @SuppressWarnings("MagicConstant")
    @Override
    public int getResultSetType() {
        return preparedStatementExecutor.getResultSetType();
    }
    
    @SuppressWarnings("MagicConstant")
    @Override
    public int getResultSetConcurrency() {
        return preparedStatementExecutor.getResultSetConcurrency();
    }
    
    @Override
    public int getResultSetHoldability() {
        return preparedStatementExecutor.getResultSetHoldability();
    }
    
    @Override
    public boolean isAccumulate() {
        return !connection.getRuntimeContext().getRule().isAllBroadcastTables(routeResult.getSqlStatementContext().getTablesContext().getTableNames());
    }
    
    @Override
    public Collection<PreparedStatement> getRoutedStatements() {
        return Collections2.transform(preparedStatementExecutor.getStatements(), new Function<Statement, PreparedStatement>() {
            @Override
            public PreparedStatement apply(final Statement input) {
                return (PreparedStatement) input;
            }
        });
    }
}
