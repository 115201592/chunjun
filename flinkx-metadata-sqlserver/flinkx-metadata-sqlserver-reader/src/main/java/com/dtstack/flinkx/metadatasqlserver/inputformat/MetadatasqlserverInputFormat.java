/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.metadatasqlserver.inputformat;

import com.dtstack.flinkx.constants.ConstantValue;
import com.dtstack.flinkx.metadata.MetaDataCons;
import com.dtstack.flinkx.metadata.inputformat.BaseMetadataInputFormat;
import com.dtstack.flinkx.metadatasqlserver.constants.SqlServerMetadataCons;
import com.dtstack.flinkx.util.ExceptionUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.flink.types.Row;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_COLUMN_NAME;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_COLUMN_PRIMARY;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_TABLE_CREATE_TIME;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_FALSE;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_INDEX_NAME;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_TABLE_ROWS;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_TABLE_COMMENT;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_TABLE_TOTAL_SIZE;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_TRUE;
import static com.dtstack.flinkx.metadatasqlserver.constants.SqlServerMetadataCons.KEY_SCHEMA_NAME;
import static com.dtstack.flinkx.metadatasqlserver.constants.SqlServerMetadataCons.KEY_TABLE_NAME;
import static com.dtstack.flinkx.metadatasqlserver.constants.SqlServerMetadataCons.KEY_ZERO;

/**
 * @author : kunni@dtstack.com
 * @date : 2020/08/06
 */

public class MetadatasqlserverInputFormat extends BaseMetadataInputFormat {

    private static final long serialVersionUID = 1L;

    protected String schema;

    protected String table;

    @Override
    protected List<Object> showTables() throws SQLException {
        List<Object> tableNameList = new LinkedList<>();
        try (ResultSet rs = statement.get().executeQuery(SqlServerMetadataCons.SQL_SHOW_TABLES)) {
            while (rs.next()) {
                tableNameList.add(Pair.of(rs.getString(1), rs.getString(2)));
            }
        }
        return tableNameList;
    }

    @Override
    protected void switchDatabase(String databaseName) throws SQLException {
        // database 以数字开头时，需要双引号
        statement.get().execute(String.format(SqlServerMetadataCons.SQL_SWITCH_DATABASE, databaseName));
    }

    @Override
    protected Row nextRecordInternal(Row row) throws IOException{
        Map<String, Object> metaData = new HashMap<>(16);
        metaData.put(MetaDataCons.KEY_OPERA_TYPE, MetaDataCons.DEFAULT_OPERA_TYPE);

        if(queryTable){
            Pair<String, String> pair = (Pair) tableIterator.get().next();
            schema = pair.getKey();
            table = pair.getValue();
        }else{
            Map<String, String> map = (Map<String, String>)tableIterator.get().next();
            schema = map.get(KEY_SCHEMA_NAME);
            table = map.get(KEY_TABLE_NAME);
        }
        String tableName = schema + ConstantValue.POINT_SYMBOL + table;
        metaData.put(MetaDataCons.KEY_SCHEMA, currentDb.get());
        metaData.put(MetaDataCons.KEY_TABLE, table);
        metaData.put(SqlServerMetadataCons.KEY_TABLE_SCHEMA, schema);
        try {
            metaData.putAll(queryMetaData(tableName));
            metaData.put(MetaDataCons.KEY_QUERY_SUCCESS, true);
        } catch (Exception e) {
            metaData.put(MetaDataCons.KEY_QUERY_SUCCESS, false);
            metaData.put(MetaDataCons.KEY_ERROR_MSG, ExceptionUtil.getErrorMessage(e));
            LOG.error(ExceptionUtil.getErrorMessage(e));
        }
        return Row.of(metaData);
    }

    @Override
    protected Map<String, Object> queryMetaData(String tableName) throws SQLException {
        Map<String, Object> result = new HashMap<>(16);
        Map<String, Object> tableProperties = queryTableProp();
        result.put(MetaDataCons.KEY_TABLE_PROPERTIES, tableProperties);
        List<Map<String, Object>> column = queryColumn();
        String partitionKey = queryPartitionColumn();
        List<Map<String, Object>> partitionColumn = new ArrayList<>();
        if(StringUtils.isNotEmpty(partitionKey)){
            column.removeIf((Map<String, Object> perColumn)->
            {
                if(StringUtils.equals(partitionKey, (String) perColumn.get(KEY_COLUMN_NAME))){
                    partitionColumn.add(perColumn);
                    return true;
                }else {
                    return false;
                }
            });
        }
        result.put(SqlServerMetadataCons.KEY_PARTITION_COLUMN, partitionColumn);
        result.put(MetaDataCons.KEY_COLUMN, column);
        List<Map<String, String>> index = queryIndex();
        result.put(MetaDataCons.KEY_COLUMN_INDEX, index);
        List<Map<String, String>> partition = queryPartition();
        result.put(MetaDataCons.KEY_PARTITIONS, partition);
        return result;
    }

    protected List<Map<String, String>> queryPartition() throws SQLException{
        List<Map<String, String>> index = new ArrayList<>();
        String sql = String.format(SqlServerMetadataCons.SQL_SHOW_PARTITION, quote(table), quote(schema));
        try(ResultSet resultSet = statement.get().executeQuery(sql)){
            while (resultSet.next()){
                Map<String, String> perIndex = new HashMap<>(16);
                perIndex.put(KEY_COLUMN_NAME, resultSet.getString(1));
                perIndex.put(KEY_TABLE_ROWS,  resultSet.getString(2));
                perIndex.put(KEY_TABLE_CREATE_TIME, resultSet.getString(3));
                perIndex.put(SqlServerMetadataCons.KEY_FILE_GROUP_NAME, resultSet.getString(4));
                index.add(perIndex);
            }
        }
        return index;
    }

    protected List<Map<String, String>> queryIndex() throws SQLException{
        List<Map<String, String>> index = new ArrayList<>();
        String sql = String.format(SqlServerMetadataCons.SQL_SHOW_TABLE_INDEX, quote(table), quote(schema));
        try(ResultSet resultSet = statement.get().executeQuery(sql)){
            while (resultSet.next()){
                Map<String, String> perIndex = new HashMap<>(16);
                perIndex.put(KEY_INDEX_NAME, resultSet.getString(1));
                perIndex.put(MetaDataCons.KEY_COLUMN_NAME,  resultSet.getString(2));
                perIndex.put(MetaDataCons.KEY_COLUMN_TYPE, resultSet.getString(3));
                index.add(perIndex);
            }
        }
        return index;
    }

    protected String queryPartitionColumn() throws SQLException{
        String partitionKey = null;
        String sql = String.format(SqlServerMetadataCons.SQL_SHOW_PARTITION_COLUMN, quote(table), quote(schema));
        try(ResultSet resultSet = statement.get().executeQuery(sql)){
            while (resultSet.next()){
                partitionKey = resultSet.getString(1);
            }
        }
        return partitionKey;
    }

    protected List<Map<String, Object>> queryColumn() throws SQLException {
        List<Map<String, Object>> column = new ArrayList<>();
        String sql = String.format(SqlServerMetadataCons.SQL_SHOW_TABLE_COLUMN, quote(table), quote(schema));
        try(ResultSet resultSet = statement.get().executeQuery(sql)){
            while(resultSet.next()){
                Map<String, Object> perColumn = new HashMap<>(16);
                perColumn.put(KEY_COLUMN_NAME, resultSet.getString(1));
                perColumn.put(MetaDataCons.KEY_COLUMN_TYPE, resultSet.getString(2));
                perColumn.put(MetaDataCons.KEY_COLUMN_COMMENT, resultSet.getString(3));
                perColumn.put(MetaDataCons.KEY_COLUMN_NULL, StringUtils.equals(resultSet.getString(4), KEY_ZERO) ? KEY_FALSE : KEY_TRUE);
                perColumn.put(MetaDataCons.KEY_COLUMN_SCALE, resultSet.getString(5));
                perColumn.put(MetaDataCons.KEY_COLUMN_DEFAULT, resultSet.getString(6));
                perColumn.put(MetaDataCons.KEY_COLUMN_INDEX, column.size()+1);
                column.add(perColumn);
            }
        }
        sql = String.format(SqlServerMetadataCons.SQL_QUERY_PRIMARY_KEY, quote(table), quote(schema));
        String primaryKey = null;
        try(ResultSet resultSet = statement.get().executeQuery(sql)){
            while(resultSet.next()){
                primaryKey = resultSet.getString(1);
            }
        }
        for(Map<String, Object> perColumn : column){
            perColumn.put(KEY_COLUMN_PRIMARY, StringUtils.equals((String) perColumn.get(KEY_COLUMN_NAME), primaryKey) ? KEY_TRUE : KEY_FALSE);
        }
        return column;
    }

    protected Map<String, Object> queryTableProp() throws SQLException{
        Map<String, Object> tableProperties = new HashMap<>(16);
        String sql = String.format(SqlServerMetadataCons.SQL_SHOW_TABLE_PROPERTIES, quote(table), quote(schema));
        try(ResultSet resultSet = statement.get().executeQuery(sql)){
            while(resultSet.next()){
                tableProperties.put(KEY_TABLE_CREATE_TIME, resultSet.getString(1));
                tableProperties.put(KEY_TABLE_ROWS, resultSet.getString(2));
                tableProperties.put(KEY_TABLE_TOTAL_SIZE, resultSet.getString(3));
                tableProperties.put(KEY_TABLE_COMMENT, resultSet.getString(4));
            }
        }
        if(tableProperties.size()==0){
            throw new SQLException(String.format("no such table(schema=%s,table=%s) in database", schema, table));
        }
        tableProperties.put(SqlServerMetadataCons.KEY_TABLE_SCHEMA, schema);
        return tableProperties;
    }

    @Override
    protected String quote(String name) {
        return "'" + name + "'";
    }

}
