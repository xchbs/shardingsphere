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

package org.apache.shardingsphere.infra.schema.refresh.impl;

import com.google.common.collect.Lists;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.schema.model.ShardingSphereSchema;
import org.apache.shardingsphere.infra.schema.model.addressing.TableAddressingMetaData;
import org.apache.shardingsphere.infra.schema.model.schema.physical.model.table.PhysicalTableMetaData;
import org.apache.shardingsphere.infra.schema.refresh.MetaDataRefreshStrategy;
import org.apache.shardingsphere.infra.schema.refresh.TableMetaDataLoaderCallback;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.CreateViewStatement;

import java.util.Collection;

/**
 * Create view statement meta data refresh strategy.
 */
public final class CreateViewStatementMetaDataRefreshStrategy implements MetaDataRefreshStrategy<CreateViewStatement> {
    
    @Override
    public void refreshMetaData(final ShardingSphereSchema schema, final DatabaseType databaseType, final Collection<String> routeDataSourceNames,
                                final CreateViewStatement sqlStatement, final TableMetaDataLoaderCallback callback) {
        String viewName = sqlStatement.getView().getTableName().getIdentifier().getValue();
        schema.getSchemaMetaData().put(viewName, new PhysicalTableMetaData());
        refreshTableAddressingMetaData(schema.getTableAddressingMetaData(), viewName, routeDataSourceNames);
    }
    
    private void refreshTableAddressingMetaData(final TableAddressingMetaData tableAddressingMetaData, final String tableName, final Collection<String> routeDataSourceNames) {
        for (String each : routeDataSourceNames) {
            refreshTableAddressingMetaData(tableAddressingMetaData, tableName, each);
        }
    }
    
    private void refreshTableAddressingMetaData(final TableAddressingMetaData tableAddressingMetaData, final String tableName, final String dataSourceName) {
        Collection<String> previousDataSourceNames = tableAddressingMetaData.getTableDataSourceNamesMapper().putIfAbsent(tableName, Lists.newArrayList(dataSourceName));
        if (null != previousDataSourceNames) {
            previousDataSourceNames.add(dataSourceName);
        }
    }
}
