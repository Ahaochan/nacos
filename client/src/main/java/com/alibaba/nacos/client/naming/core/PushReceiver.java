/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
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
 */

package com.alibaba.nacos.client.naming.core;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.naming.cache.ServiceInfoHolder;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.utils.IoUtils;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import com.alibaba.nacos.common.utils.ThreadUtils;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;

/**
 * Push receiver.
 *
 * @author xuanyin
 */
public class PushReceiver implements Runnable, Closeable {
    
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    
    private static final int UDP_MSS = 64 * 1024;
    
    private static final String PUSH_PACKAGE_TYPE_DOM = "dom";
    
    private static final String PUSH_PACKAGE_TYPE_SERVICE = "service";
    
    private static final String PUSH_PACKAGE_TYPE_DUMP = "dump";
    
    private ScheduledExecutorService executorService;
    
    private DatagramSocket udpSocket;
    
    private ServiceInfoHolder serviceInfoHolder;
    
    private volatile boolean closed = false;
    
    public static String getPushReceiverUdpPort() {
        return System.getenv(PropertyKeyConst.PUSH_RECEIVER_UDP_PORT);
    }
    
    public PushReceiver(ServiceInfoHolder serviceInfoHolder) {
        try {
            this.serviceInfoHolder = serviceInfoHolder;
            String udpPort = getPushReceiverUdpPort();
            // 创建一个UDP Socket, 用于接收服务端反向推送过来的的UDP数据包
            if (StringUtils.isEmpty(udpPort)) {
                this.udpSocket = new DatagramSocket();
            } else {
                this.udpSocket = new DatagramSocket(new InetSocketAddress(Integer.parseInt(udpPort)));
            }
            this.executorService = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("com.alibaba.nacos.naming.push.receiver");
                return thread;
            });
            
            this.executorService.execute(this);
        } catch (Exception e) {
            NAMING_LOGGER.error("[NA] init udp socket failed", e);
        }
    }
    
    @Override
    public void run() {
        while (!closed) {
            try {
                
                // byte[] is initialized with 0 full filled by default
                byte[] buffer = new byte[UDP_MSS];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // 接收服务端反向推送的UDP数据包，然后赋值给DatagramPacket
                udpSocket.receive(packet);

                // 解包UDP数据包, 反序列化
                String json = new String(IoUtils.tryDecompress(packet.getData()), UTF_8).trim();
                NAMING_LOGGER.info("received push data: " + json + " from " + packet.getAddress().toString());
                
                PushPacket pushPacket = JacksonUtils.toObj(json, PushPacket.class);
                String ack;
                if (PUSH_PACKAGE_TYPE_DOM.equals(pushPacket.type) || PUSH_PACKAGE_TYPE_SERVICE.equals(pushPacket.type)) {
                    // 然后进行业务逻辑处理
                    serviceInfoHolder.processServiceInfo(pushPacket.data);
                    
                    // send ack to server
                    ack = "{\"type\": \"push-ack\"" + ", \"lastRefTime\":\"" + pushPacket.lastRefTime + "\", \"data\":"
                            + "\"\"}";
                } else if (PUSH_PACKAGE_TYPE_DUMP.equals(pushPacket.type)) {
                    // dump data to server
                    ack = "{\"type\": \"dump-ack\"" + ", \"lastRefTime\": \"" + pushPacket.lastRefTime + "\", \"data\":"
                            + "\"" + StringUtils.escapeJavaScript(JacksonUtils.toJson(serviceInfoHolder.getServiceInfoMap()))
                            + "\"}";
                } else {
                    // do nothing send ack only
                    ack = "{\"type\": \"unknown-ack\"" + ", \"lastRefTime\":\"" + pushPacket.lastRefTime
                            + "\", \"data\":" + "\"\"}";
                }

                // 处理完毕后, 发送ACK给服务端
                udpSocket.send(new DatagramPacket(ack.getBytes(UTF_8), ack.getBytes(UTF_8).length,
                        packet.getSocketAddress()));
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                NAMING_LOGGER.error("[NA] error while receiving push data", e);
            }
        }
    }
    
    @Override
    public void shutdown() throws NacosException {
        String className = this.getClass().getName();
        NAMING_LOGGER.info("{} do shutdown begin", className);
        ThreadUtils.shutdownThreadPool(executorService, NAMING_LOGGER);
        closed = true;
        udpSocket.close();
        NAMING_LOGGER.info("{} do shutdown stop", className);
    }
    
    public static class PushPacket {
        
        public String type;
        
        public long lastRefTime;
        
        public String data;
    }
    
    public int getUdpPort() {
        return this.udpSocket.getLocalPort();
    }
}
