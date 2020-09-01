/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.websocket.format;

import com.dtstack.flinkx.inputformat.BaseRichInputFormat;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.types.Row;
import org.java_websocket.WebSocket;

import java.io.IOException;
import java.net.URI;

/** 读取指定WebSocketUrl中的数据
 * @Company: www.dtstack.com
 * @author kunni
 */

public class WebSocketInputFormat extends BaseRichInputFormat {

    private String serverUrl;

    private DtWebSocketClient client;


    @Override
    protected void openInternal(InputSplit inputSplit) throws IOException {
        try{
            client = new DtWebSocketClient(new URI(serverUrl));
            // connect 启动异步线程
            client.connect();
            while (!client.getReadyState().equals(WebSocket.READYSTATE.OPEN)) {
                System.out.println("连接中···请稍后");
                Thread.sleep(1000);
            }
        }catch (Exception ce){

        }

    }

    @Override
    protected InputSplit[] createInputSplitsInternal(int i) throws Exception {
        return new InputSplit[0];
    }

    @Override
    protected Row nextRecordInternal(Row row) throws IOException {
        return null;
    }

    @Override
    protected void closeInternal() throws IOException {

    }

    @Override
    public boolean reachedEnd() throws IOException {
        return false;
    }

    public void setServerUrl(String serverUrl){
        this.serverUrl = serverUrl;
    }

}
