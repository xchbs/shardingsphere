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

package org.apache.shardingsphere.scaling.core.execute.executor.importer;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.scaling.core.config.ImporterConfiguration;
import org.apache.shardingsphere.scaling.core.constant.ScalingConstant;
import org.apache.shardingsphere.scaling.core.datasource.DataSourceManager;
import org.apache.shardingsphere.scaling.core.exception.SyncTaskExecuteException;
import org.apache.shardingsphere.scaling.core.execute.executor.AbstractShardingScalingExecutor;
import org.apache.shardingsphere.scaling.core.execute.executor.channel.Channel;
import org.apache.shardingsphere.scaling.core.execute.executor.record.DataRecord;
import org.apache.shardingsphere.scaling.core.execute.executor.record.FinishedRecord;
import org.apache.shardingsphere.scaling.core.execute.executor.record.Record;
import org.apache.shardingsphere.scaling.core.job.position.IncrementalPosition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstract JDBC importer implementation.
 */
@Slf4j
public abstract class AbstractJDBCImporter extends AbstractShardingScalingExecutor<IncrementalPosition> implements Importer {
    
    private final ImporterConfiguration importerConfig;
    
    private final DataSourceManager dataSourceManager;
    
    private final AbstractSQLBuilder sqlBuilder;
    
    @Setter
    private Channel channel;
    
    protected AbstractJDBCImporter(final ImporterConfiguration importerConfig, final DataSourceManager dataSourceManager) {
        this.importerConfig = importerConfig;
        this.dataSourceManager = dataSourceManager;
        sqlBuilder = createSQLBuilder(importerConfig.getShardingColumnsMap());
    }
    
    /**
     * Create SQL builder.
     *
     * @return SQL builder
     */
    protected abstract AbstractSQLBuilder createSQLBuilder(Map<String, Set<String>> shardingColumnsMap);
    
    @Override
    public final void start() {
        super.start();
        write();
    }
    
    @Override
    public final void write() {
        while (isRunning()) {
            List<Record> records = channel.fetchRecords(100, 3);
            if (null != records && !records.isEmpty()) {
                flush(dataSourceManager.getDataSource(importerConfig.getDataSourceConfiguration()), records);
                if (FinishedRecord.class.equals(records.get(records.size() - 1).getClass())) {
                    channel.ack();
                    break;
                }
            }
            channel.ack();
        }
    }
    
    private void flush(final DataSource dataSource, final List<Record> buffer) {
        boolean success = tryFlush(dataSource, buffer);
        if (isRunning() && !success) {
            throw new SyncTaskExecuteException("write failed.");
        }
    }
    
    private boolean tryFlush(final DataSource dataSource, final List<Record> buffer) {
        int retryTimes = importerConfig.getRetryTimes();
        List<Record> unflushed = buffer;
        do {
            unflushed = doFlush(dataSource, unflushed);
        } while (isRunning() && !unflushed.isEmpty() && retryTimes-- > 0);
        return unflushed.isEmpty();
    }
    
    private List<Record> doFlush(final DataSource dataSource, final List<Record> buffer) {
        int i = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            for (; i < buffer.size(); i++) {
                execute(connection, buffer.get(i));
            }
            connection.commit();
        } catch (final SQLException ex) {
            log.error("flush failed: {}", buffer.get(i), ex);
            return buffer.subList(i, buffer.size());
        }
        return Collections.emptyList();
    }
    
    private void execute(final Connection connection, final Record record) throws SQLException {
        if (DataRecord.class.equals(record.getClass())) {
            DataRecord dataRecord = (DataRecord) record;
            switch (dataRecord.getType()) {
                case ScalingConstant.INSERT:
                    executeInsert(connection, dataRecord);
                    break;
                case ScalingConstant.UPDATE:
                    executeUpdate(connection, dataRecord);
                    break;
                case ScalingConstant.DELETE:
                    executeDelete(connection, dataRecord);
                    break;
                default:
                    break;
            }
        }
    }
    
    private void executeInsert(final Connection connection, final DataRecord record) throws SQLException {
        try {
            executeSQL(connection, record, sqlBuilder.buildInsertSQL(record));
        } catch (final SQLIntegrityConstraintViolationException ignored) {
        }
    }
    
    private void executeUpdate(final Connection connection, final DataRecord record) throws SQLException {
        executeSQL(connection, record, sqlBuilder.buildUpdateSQL(record));
    }
    
    private void executeDelete(final Connection connection, final DataRecord record) throws SQLException {
        executeSQL(connection, record, sqlBuilder.buildDeleteSQL(record));
    }
    
    private void executeSQL(final Connection connection, final DataRecord record, final PreparedSQL preparedSQL) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(preparedSQL.getSql());
        for (int i = 0; i < preparedSQL.getValuesIndex().size(); i++) {
            int columnIndex = preparedSQL.getValuesIndex().get(i);
            ps.setObject(i + 1, record.getColumn(columnIndex).getValue());
        }
        ps.execute();
    }
}
