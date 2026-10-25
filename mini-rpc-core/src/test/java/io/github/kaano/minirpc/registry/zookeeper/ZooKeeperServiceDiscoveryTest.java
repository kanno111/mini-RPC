package io.github.kaano.minirpc.registry.zookeeper;

import io.github.kaano.minirpc.registry.ServiceAddress;
import io.github.kaano.minirpc.common.ServiceKey;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.BackgroundPathable;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.api.GetChildrenBuilder;
import org.junit.jupiter.api.Test;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/** 验证 ZooKeeper 服务发现的本地地址缓存。 */
@SuppressWarnings("unchecked")
class ZooKeeperServiceDiscoveryTest {

    @Test
    void shouldReuseCachedAddressesForRepeatedDiscovery() throws Exception {
        CuratorFramework client = mock(CuratorFramework.class);
        GetChildrenBuilder childrenBuilder = mock(GetChildrenBuilder.class);
        BackgroundPathable<List<String>> watchedChildren =
                mock(BackgroundPathable.class);
        ServiceKey serviceKey = new ServiceKey(
                "io.github.kaano.minirpc.example.api.HelloService",
                "gray",
                "2.0"
        );
        String servicePath = ZooKeeperServiceRegistry.buildServicePath(
                serviceKey
        );

        when(client.getChildren()).thenReturn(childrenBuilder);
        when(childrenBuilder.usingWatcher(any(CuratorWatcher.class)))
                .thenReturn(watchedChildren);
        when(watchedChildren.forPath(servicePath))
                .thenReturn(List.of("127.0.0.1%3A8080"));

        try (ZooKeeperServiceDiscovery discovery =
                     new ZooKeeperServiceDiscovery(
                             client,
                             Duration.ofSeconds(3)
                     )) {
            List<ServiceAddress> firstResult =
                    discovery.discover(serviceKey);
            List<ServiceAddress> secondResult =
                    discovery.discover(serviceKey);

            assertEquals(
                    List.of(new ServiceAddress("127.0.0.1", 8080)),
                    firstResult
            );
            assertEquals(firstResult, secondResult);

            /*
             * discover 调用了两次，但缓存仍在 3 秒有效期内，所以底层 ZooKeeper
             * 的 forPath() 只能执行一次。这是本测试最重要的断言。
             */
            verify(watchedChildren, times(1)).forPath(servicePath);
        }

        verify(client, times(1)).close();
    }

    @Test
    void shouldInvalidateCacheWhenZooKeeperWatchReceivesChange()
            throws Exception {
        CuratorFramework client = mock(CuratorFramework.class);
        GetChildrenBuilder childrenBuilder = mock(GetChildrenBuilder.class);
        BackgroundPathable<List<String>> watchedChildren =
                mock(BackgroundPathable.class);
        ServiceKey serviceKey = new ServiceKey(
                "io.github.kaano.minirpc.example.api.HelloService",
                "gray",
                "2.0"
        );
        String servicePath = ZooKeeperServiceRegistry.buildServicePath(
                serviceKey
        );

        when(client.getChildren()).thenReturn(childrenBuilder);
        when(childrenBuilder.usingWatcher(any(CuratorWatcher.class)))
                .thenReturn(watchedChildren);
        when(watchedChildren.forPath(servicePath))
                .thenReturn(
                        List.of("127.0.0.1%3A8080"),
                        List.of("127.0.0.1%3A8081")
                );

        try (ZooKeeperServiceDiscovery discovery =
                     new ZooKeeperServiceDiscovery(
                             client,
                             Duration.ofSeconds(3)
                     )) {
            assertEquals(
                    List.of(new ServiceAddress("127.0.0.1", 8080)),
                    discovery.discover(serviceKey)
            );

            ArgumentCaptor<CuratorWatcher> watcherCaptor =
                    ArgumentCaptor.forClass(CuratorWatcher.class);
            verify(childrenBuilder, atLeastOnce())
                    .usingWatcher(watcherCaptor.capture());

            /*
             * 模拟 ZooKeeper 通知“服务目录的子节点发生变化”。真实环境中，
             * Provider 8080 下线或 8081 上线都会产生这类事件。
             */
            watcherCaptor.getValue().process(new WatchedEvent(
                    Watcher.Event.EventType.NodeChildrenChanged,
                    Watcher.Event.KeeperState.SyncConnected,
                    servicePath
            ));

            assertEquals(
                    List.of(new ServiceAddress("127.0.0.1", 8081)),
                    discovery.discover(serviceKey)
            );

            // Watch 删除缓存后，第二次 discover 必须重新访问 ZooKeeper。
            verify(watchedChildren, times(2)).forPath(servicePath);
        }
    }

    @Test
    void shouldCacheDifferentVersionsIndependently() throws Exception {
        CuratorFramework client = mock(CuratorFramework.class);
        GetChildrenBuilder childrenBuilder = mock(GetChildrenBuilder.class);
        BackgroundPathable<List<String>> watchedChildren =
                mock(BackgroundPathable.class);
        ServiceKey versionOne = new ServiceKey(
                "com.example.PaymentService",
                "default",
                "1.0"
        );
        ServiceKey versionTwo = new ServiceKey(
                "com.example.PaymentService",
                "default",
                "2.0"
        );
        String versionOnePath = ZooKeeperServiceRegistry.buildServicePath(
                versionOne
        );
        String versionTwoPath = ZooKeeperServiceRegistry.buildServicePath(
                versionTwo
        );

        when(client.getChildren()).thenReturn(childrenBuilder);
        when(childrenBuilder.usingWatcher(any(CuratorWatcher.class)))
                .thenReturn(watchedChildren);
        when(watchedChildren.forPath(versionOnePath))
                .thenReturn(List.of("127.0.0.1%3A8080"));
        when(watchedChildren.forPath(versionTwoPath))
                .thenReturn(List.of("127.0.0.1%3A8081"));

        try (ZooKeeperServiceDiscovery discovery =
                     new ZooKeeperServiceDiscovery(
                             client,
                             Duration.ofSeconds(3)
                     )) {
            assertEquals(
                    List.of(new ServiceAddress("127.0.0.1", 8080)),
                    discovery.discover(versionOne)
            );
            assertEquals(
                    List.of(new ServiceAddress("127.0.0.1", 8081)),
                    discovery.discover(versionTwo)
            );

            // 再次查询应分别命中两个缓存，而不是按接口名互相覆盖。
            discovery.discover(versionOne);
            discovery.discover(versionTwo);
            verify(watchedChildren, times(1)).forPath(versionOnePath);
            verify(watchedChildren, times(1)).forPath(versionTwoPath);
        }
    }
}
