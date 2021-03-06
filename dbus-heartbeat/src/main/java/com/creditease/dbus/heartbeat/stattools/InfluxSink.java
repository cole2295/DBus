/*-
 * <<
 * DBus
 * ==
 * Copyright (C) 2016 - 2018 Bridata
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package com.creditease.dbus.heartbeat.stattools;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Properties;

import javax.xml.bind.PropertyException;

import com.creditease.dbus.commons.Constants;
import com.creditease.dbus.commons.StatMessage;
import com.creditease.dbus.heartbeat.log.LoggerFactory;
import com.creditease.dbus.heartbeat.util.ConfUtils;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;

/**
 * Created by dongwang47 on 2016/9/2.
 */
public class InfluxSink {

    private Logger LOG = LoggerFactory.getLogger();
    private final static String CONFIG_PROPERTIES ="stat_config.properties";

    private String tableName = null;
    private String postURL = null;
    private URI uri = null;
    private HttpClient client = null;
    private HttpPost post = null;

    final private int CUSTOM_TIME_OUT = 1000; // 超时时间为1000毫秒

    public InfluxSink() throws IOException, PropertyException {
        Properties configProps = ConfUtils.getProps(CONFIG_PROPERTIES);
        String dbURL = configProps.getProperty(Constants.InfluxDB.DB_URL);
        String dbName = configProps.getProperty(Constants.InfluxDB.DB_NAME);
        tableName = configProps.getProperty(Constants.InfluxDB.TABLE_NAME);
        if (dbURL == null) {
            throw new PropertyException("配置参数文件内容不能为空！ " + Constants.InfluxDB.DB_URL);
        }
        if (dbName == null) {
            throw new PropertyException("配置参数文件内容不能为空！ " + Constants.InfluxDB.DB_NAME);
        }
        if (tableName == null) {
            throw new PropertyException("配置参数文件内容不能为空！ " + Constants.InfluxDB.TABLE_NAME);
        }

        postURL = String.format("%s/write?db=%s", dbURL, dbName);
        initPost();
    }

    private void initPost() {
        try {
            uri = new URI(postURL);
            client = HttpClientBuilder.create().build();
            post = new HttpPost();
            post.addHeader("Connection", "Keep-Alive");
            LOG.warn("重新生成 HttpPost: uri={}, post={}, client={}", uri, post, client);
        } catch (Exception e) {
            LOG.error(String.format("URI不正确，URI=%s", postURL), e);
        }
    }


    private String statMessageToLineProtocol(Long offset, StatMessage msg) {
        String fullSchemaName = msg.getDsName() + "." + msg.getSchemaName();
        String fullTableName = fullSchemaName + "." + msg.getTableName();
        String keys = String.format("type=%s,ds=%s,schema=%s,table=%s", msg.getType(),
                msg.getDsName(), fullSchemaName, fullTableName);

        String fields = String.format("count=%d,errorCount=%d,warningCount=%d,latency=%f,offset=%d",
                msg.getCount(), msg.getErrorCount(), msg.getWarningCount(),((float)msg.getLatencyMS())/1000, offset);

        //time should by Nanoseconds
        long timestamp = msg.getTxTimeMS() * 1000000;

        return String.format ("%s,%s %s %d", tableName, keys, fields, timestamp);
    }

    public int sendMessage(Long offset, StatMessage msg,  long retryTimes) {
        String content = null;
        HttpResponse response = null;
        try {
            post.setURI(uri);

            // add header
            content = statMessageToLineProtocol(offset, msg);
            post.setEntity(new StringEntity(content));
            post.setConfig(RequestConfig.custom().setConnectionRequestTimeout(CUSTOM_TIME_OUT).setConnectTimeout(CUSTOM_TIME_OUT).setSocketTimeout(CUSTOM_TIME_OUT).build());
            response = client.execute(post);

            int code = response.getStatusLine().getStatusCode();

            if (code == 200 || code == 204) {
                LOG.info(String.format("Sink to influxdb OK! http_code=%d, content=%s", code, content));
                return 0;
            } else {
                LOG.warn(String.format("http_code=%d! try %d times -- Sink to influxdb failed! url=%s, content=%s",
                        code, retryTimes, postURL, content));
                initPost();
                return -1;
            }
        } catch (Exception e) {
            LOG.warn(String.format("Reason:%s. try %d times -- Sink to influxdb failed! url=%s, content=%s",
                    e.getMessage(), retryTimes, postURL, content));
            initPost();
            return -1;
        }
    }

    public int sendBatchMessages(Map<Long, StatMessage> map, long retryTimes) throws IOException {
        int ret;
        for (Map.Entry<Long, StatMessage> entry : map.entrySet()) {
            ret = sendMessage(entry.getKey(), entry.getValue(), retryTimes);
            if (ret != 0) {
                return ret;
            }
        }

        return 0;
    }

    public void cleanUp() {
        ;
    }

    public static void main(String[] args) {
        HttpClient client = HttpClientBuilder.create().build();
        System.out.println(client);
    }
}
