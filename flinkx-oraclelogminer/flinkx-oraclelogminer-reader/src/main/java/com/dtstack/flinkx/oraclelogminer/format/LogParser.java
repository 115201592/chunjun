/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.dtstack.flinkx.oraclelogminer.format;

import com.dtstack.flinkx.oraclelogminer.entity.QueueData;
import com.dtstack.flinkx.util.SnowflakeIdWorker;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.*;

/**
 * @author jiangbo
 * @date 2020/3/30
 */
public class LogParser {

    public static Logger LOG = LoggerFactory.getLogger(LogParser.class);

    public static SnowflakeIdWorker idWorker = new SnowflakeIdWorker(1, 1);

    private LogMinerConfig config;

    public LogParser(LogMinerConfig config) {
        this.config = config;
    }

    private static String cleanString(String str) {
        if("NULL".equalsIgnoreCase(str)){
            return "";
        }

        if (str.startsWith("TIMESTAMP")) {
            str = str.replace("TIMESTAMP ", "");
        }

        if (str.startsWith("'") && str.endsWith("'")) {
            str = str.substring(1, str.length() - 1);
        }

        if (str.startsWith("\"") && str.endsWith("\"")) {
            str = str.substring(1, str.length() - 1);
        }

        return str.replace("IS NULL","= NULL").trim();
    }

    private static void parseInsertStmt(Insert insert, LinkedHashMap<String,String> beforeDataMap, LinkedHashMap<String,String> afterDataMap){
        for (Column column : insert.getColumns()){
            afterDataMap.put(cleanString(column.getColumnName()), null);
        }

        ExpressionList eList = (ExpressionList) insert.getItemsList();
        List<Expression> valueList = eList.getExpressions();
        int i =0;
        for (String key : afterDataMap.keySet()){
            String value = cleanString(valueList.get(i).toString());
            afterDataMap.put(key, value);
            beforeDataMap.put(key, null);
            i++;
        }
    }

    private static void parseUpdateStmt(Update update, LinkedHashMap<String,String> beforeDataMap, LinkedHashMap<String,String> afterDataMap, String sqlRedo){
        Iterator<Expression> iterator = update.getExpressions().iterator();
        for (Column c : update.getColumns()){
            afterDataMap.put(cleanString(c.getColumnName()), cleanString(iterator.next().toString()));
        }

        if(update.getWhere() != null){
            update.getWhere().accept(new ExpressionVisitorAdapter() {
                @Override
                public void visit(final EqualsTo expr){
                    String col = cleanString(expr.getLeftExpression().toString());
                    if(afterDataMap.containsKey(col)){
                        String value = cleanString(expr.getRightExpression().toString());
                        beforeDataMap.put(col, value);
                    } else {
                        String value = cleanString(expr.getRightExpression().toString());
                        beforeDataMap.put(col, value);
                        afterDataMap.put(col, value);
                    }
                }
            });
        }else{
            LOG.error("where is null when LogParser parse sqlRedo, sqlRedo = {}, update = {}", sqlRedo, update.toString());
        }
    }

    private static void parseDeleteStmt(Delete delete, LinkedHashMap<String,String> beforeDataMap, LinkedHashMap<String,String> afterDataMap){
        delete.getWhere().accept(new ExpressionVisitorAdapter(){
            @Override
            public void visit(final EqualsTo expr){
                String col = cleanString(expr.getLeftExpression().toString());
                String value = cleanString(expr.getRightExpression().toString());
                beforeDataMap.put(col, value);
                afterDataMap.put(col, null);
            }
        });
    }

    public QueueData parse(QueueData pair) throws JSQLParserException {
        Map<String, Object> logData = pair.getData();
        String schema = MapUtils.getString(logData, "schema");
        String tableName = MapUtils.getString(logData, "tableName");
        String operation = MapUtils.getString(logData, "operation");
        String sqlLog = MapUtils.getString(logData, "sqlLog");
        String sqlRedo = sqlLog.replace("IS NULL", "= NULL");
        Timestamp timestamp = (Timestamp)MapUtils.getObject(logData, "opTime");

        Map<String,Object> message = new LinkedHashMap<>();
        message.put("scn", pair.getScn());
        message.put("type", operation);
        message.put("schema", schema);
        message.put("table", tableName);
        message.put("ts", idWorker.nextId());
        message.put("opTime", timestamp);


        LOG.debug("sqlRedo = {}", sqlRedo);
        Statement stmt = CCJSqlParserUtil.parse(sqlRedo);
        LinkedHashMap<String,String> afterDataMap = new LinkedHashMap<>();
        LinkedHashMap<String,String> beforeDataMap = new LinkedHashMap<>();

        if (stmt instanceof Insert){
            parseInsertStmt((Insert) stmt, beforeDataMap, afterDataMap);
        }else if (stmt instanceof Update){
            parseUpdateStmt((Update) stmt, beforeDataMap, afterDataMap, sqlRedo);
        }else if (stmt instanceof Delete){
            parseDeleteStmt((Delete) stmt, beforeDataMap, afterDataMap);
        }

        if (config.getPavingData()) {
            afterDataMap.forEach((key, val) -> message.put("after_" + key, val));

            beforeDataMap.forEach((key, val) -> message.put("before_" + key, val));

            return new QueueData(pair.getScn(), message);
        } else {
            message.put("before", beforeDataMap);
            message.put("after", afterDataMap);
            Map<String,Object> event = Collections.singletonMap("message", message);

            return new QueueData(pair.getScn(), event);
        }
    }
}
