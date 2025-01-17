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

package com.alibaba.nacos.client.naming.remote;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.alibaba.nacos.api.naming.pojo.Service;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.api.selector.AbstractSelector;
import com.alibaba.nacos.client.naming.cache.ServiceInfoHolder;
import com.alibaba.nacos.client.naming.core.ServerListManager;
import com.alibaba.nacos.client.naming.core.ServiceInfoUpdateService;
import com.alibaba.nacos.client.naming.event.InstancesChangeNotifier;
import com.alibaba.nacos.client.naming.remote.gprc.NamingGrpcClientProxy;
import com.alibaba.nacos.client.naming.remote.http.NamingHttpClientManager;
import com.alibaba.nacos.client.naming.remote.http.NamingHttpClientProxy;
import com.alibaba.nacos.client.security.SecurityProxy;
import com.alibaba.nacos.common.utils.ThreadUtils;

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.alibaba.nacos.client.constant.Constants.Security.SECURITY_INFO_REFRESH_INTERVAL_MILLS;
import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;

/**
 * Delegate of naming client proxy.
 *
 * @author xiweng.yy
 */
public class NamingClientProxyDelegate implements NamingClientProxy {
    
    private final ServerListManager serverListManager;
    
    private final ServiceInfoUpdateService serviceInfoUpdateService;
    
    private final ServiceInfoHolder serviceInfoHolder;
    
    private final NamingHttpClientProxy httpClientProxy;
    
    private final NamingGrpcClientProxy grpcClientProxy;
    
    private final SecurityProxy securityProxy;
    
    private ScheduledExecutorService executorService;
    
    public NamingClientProxyDelegate(String namespace, ServiceInfoHolder serviceInfoHolder, Properties properties,
            InstancesChangeNotifier changeNotifier) throws NacosException {
        // 初始化服务信息更新组件
        this.serviceInfoUpdateService = new ServiceInfoUpdateService(properties, serviceInfoHolder, this,
                changeNotifier);
        // 初始化nacos server节点管理器
        this.serverListManager = new ServerListManager(properties, namespace);
        this.serviceInfoHolder = serviceInfoHolder;
        this.securityProxy = new SecurityProxy(this.serverListManager.getServerList(), NamingHttpClientManager.getInstance().getNacosRestTemplate());
        initSecurityProxy(properties);
        // 初始化http请求客户端
        this.httpClientProxy = new NamingHttpClientProxy(namespace, securityProxy, serverListManager, properties,
                serviceInfoHolder);
        // 初始化grpc请求客户端
        this.grpcClientProxy = new NamingGrpcClientProxy(namespace, securityProxy, serverListManager, properties,
                serviceInfoHolder);
    }
    
    private void initSecurityProxy(Properties properties) {
        this.executorService = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r);
            t.setName("com.alibaba.nacos.client.naming.security");
            t.setDaemon(true);
            return t;
        });
        this.securityProxy.login(properties);
        this.executorService.scheduleWithFixedDelay(() -> securityProxy.login(properties), 0,
                SECURITY_INFO_REFRESH_INTERVAL_MILLS, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void registerService(String serviceName, String groupName, Instance instance) throws NacosException {
        // 临时节点的客户端代理实现类是httpClientProxy, 永久节点的客户端代理实现类是grpcClientProxy
        getExecuteClientProxy(instance).registerService(serviceName, groupName, instance);
    }
    
    @Override
    public void deregisterService(String serviceName, String groupName, Instance instance) throws NacosException {
        getExecuteClientProxy(instance).deregisterService(serviceName, groupName, instance);
    }
    
    @Override
    public void updateInstance(String serviceName, String groupName, Instance instance) throws NacosException {
    
    }
    
    @Override
    public ServiceInfo queryInstancesOfService(String serviceName, String groupName, String clusters, int udpPort,
            boolean healthyOnly) throws NacosException {
        return grpcClientProxy.queryInstancesOfService(serviceName, groupName, clusters, udpPort, healthyOnly);
    }
    
    @Override
    public Service queryService(String serviceName, String groupName) throws NacosException {
        return null;
    }
    
    @Override
    public void createService(Service service, AbstractSelector selector) throws NacosException {
    
    }
    
    @Override
    public boolean deleteService(String serviceName, String groupName) throws NacosException {
        return false;
    }
    
    @Override
    public void updateService(Service service, AbstractSelector selector) throws NacosException {
    
    }
    
    @Override
    public ListView<String> getServiceList(int pageNo, int pageSize, String groupName, AbstractSelector selector)
            throws NacosException {
        return grpcClientProxy.getServiceList(pageNo, pageSize, groupName, selector);
    }
    
    @Override
    public ServiceInfo subscribe(String serviceName, String groupName, String clusters) throws NacosException {
        NAMING_LOGGER.info("[SUBSCRIBE-SERVICE] service:{}, group:{}, clusters:{} ", serviceName, groupName, clusters);
        String serviceNameWithGroup = NamingUtils.getGroupedName(serviceName, groupName);
        String serviceKey = ServiceInfo.getKey(serviceNameWithGroup, clusters);
        serviceInfoUpdateService.scheduleUpdateIfAbsent(serviceName, groupName, clusters);
        ServiceInfo result = serviceInfoHolder.getServiceInfoMap().get(serviceKey);
        if (null == result || !isSubscribed(serviceName, groupName, clusters)) {
            result = grpcClientProxy.subscribe(serviceName, groupName, clusters);
        }
        serviceInfoHolder.processServiceInfo(result);
        return result;
    }
    
    @Override
    public void unsubscribe(String serviceName, String groupName, String clusters) throws NacosException {
        NAMING_LOGGER.debug("[UNSUBSCRIBE-SERVICE] service:{}, group:{}, cluster:{} ", serviceName, groupName, clusters);
        serviceInfoUpdateService.stopUpdateIfContain(serviceName, groupName, clusters);
        grpcClientProxy.unsubscribe(serviceName, groupName, clusters);
    }
    
    @Override
    public boolean isSubscribed(String serviceName, String groupName, String clusters) throws NacosException {
        return grpcClientProxy.isSubscribed(serviceName, groupName, clusters);
    }
    
    @Override
    public void updateBeatInfo(Set<Instance> modifiedInstances) {
        httpClientProxy.updateBeatInfo(modifiedInstances);
    }
    
    @Override
    public boolean serverHealthy() {
        return grpcClientProxy.serverHealthy() || httpClientProxy.serverHealthy();
    }
    
    private NamingClientProxy getExecuteClientProxy(Instance instance) {
        // 如果是临时节点, 需要心跳检测的, 就用grpc通信
        // 如果是永久节点, 就用http通信
        return instance.isEphemeral() ? grpcClientProxy : httpClientProxy;
    }
    
    @Override
    public void shutdown() throws NacosException {
        String className = this.getClass().getName();
        NAMING_LOGGER.info("{} do shutdown begin", className);
        serviceInfoUpdateService.shutdown();
        serverListManager.shutdown();
        httpClientProxy.shutdown();
        grpcClientProxy.shutdown();
        securityProxy.shutdown();
        ThreadUtils.shutdownThreadPool(executorService, NAMING_LOGGER);
        NAMING_LOGGER.info("{} do shutdown stop", className);
    }
}
