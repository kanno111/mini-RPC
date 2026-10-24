package com.itheima.server;

import com.itheima.common.RpcException;
import com.itheima.provider.ServiceProvider;
import com.itheima.registry.ServiceAddress;
import com.itheima.registry.ServiceRegistry;
import com.itheima.transport.server.RpcServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 Provider 的本地登记、端口绑定和注册地址发布顺序。
 *
 * <p>这里使用几个只记录事件的测试替身，不启动真实 Netty，也不连接真实
 * ZooKeeper。这样测试关注的只有一件事：哪个动作先发生、哪个动作后发生。</p>
 */
class RpcProviderStartupOrderTest {

    @Test
    void shouldPublishServiceOnlyAfterServerHasBoundPort() {
        List<String> events = new ArrayList<>();
        RecordingServiceProvider serviceProvider =
                new RecordingServiceProvider(events);
        RecordingServiceRegistry serviceRegistry =
                new RecordingServiceRegistry(events);
        RpcServer rpcServer = new RecordingRpcServer(events, false);

        RpcProvider provider = new RpcProvider(
                serviceProvider,
                serviceRegistry,
                rpcServer,
                new ServiceAddress("127.0.0.1", 8080)
        );

        provider.registerService(Runnable.class, () -> {
            // 测试只关心注册顺序，不需要真正执行业务逻辑。
        });

        /*
         * registerService() 结束后应该只有本地登记。
         * 此时端口还没绑定，所以绝不能提前把地址发布到注册中心。
         */
        assertEquals(List.of("local-register"), events);

        provider.start();

        assertEquals(
                List.of(
                        "local-register",
                        "port-bound",
                        "registry-register",
                        "registry-unregister",
                        "server-stop",
                        "registry-close"
                ),
                events
        );
    }

    @Test
    void shouldNotPublishServiceWhenServerCannotBindPort() {
        List<String> events = new ArrayList<>();
        RecordingServiceRegistry serviceRegistry =
                new RecordingServiceRegistry(events);
        RpcProvider provider = new RpcProvider(
                new RecordingServiceProvider(events),
                serviceRegistry,
                new RecordingRpcServer(events, true),
                new ServiceAddress("127.0.0.1", 8080)
        );

        provider.registerService(Runnable.class, () -> {
            // 测试服务实现。
        });

        assertThrows(RpcException.class, provider::start);

        assertEquals(0, serviceRegistry.registerCount);
        assertEquals(
                List.of(
                        "local-register",
                        "bind-failed",
                        "server-stop",
                        "registry-close"
                ),
                events
        );
    }

    @Test
    void shouldContinueCleanupWhenUnregisterFails() {
        List<String> events = new ArrayList<>();
        RecordingServiceRegistry serviceRegistry =
                new RecordingServiceRegistry(events, true);
        RpcProvider provider = new RpcProvider(
                new RecordingServiceProvider(events),
                serviceRegistry,
                new RecordingRpcServer(events, false),
                new ServiceAddress("127.0.0.1", 8080)
        );

        provider.registerService(Runnable.class, () -> {
            // 测试只需要让服务进入待发布列表。
        });

        RpcException exception = assertThrows(
                RpcException.class,
                provider::start
        );

        assertEquals("模拟注销失败", exception.getMessage());
        assertEquals(
                List.of(
                        "local-register",
                        "port-bound",
                        "registry-register",
                        "registry-unregister",
                        "server-stop",
                        "registry-close"
                ),
                events
        );
    }

    /** 只记录本地注册动作的 ServiceProvider 测试替身。 */
    private static final class RecordingServiceProvider
            implements ServiceProvider {

        private final List<String> events;

        private RecordingServiceProvider(List<String> events) {
            this.events = events;
        }

        @Override
        public void registerService(
                Class<?> serviceInterface,
                Object service
        ) {
            events.add("local-register");
        }

        @Override
        public Object getService(String serviceName) {
            throw new UnsupportedOperationException("本测试不需要查找服务");
        }

        @Override
        public void removeService(String serviceName) {
            events.add("local-remove");
        }
    }

    /** 只记录发布和关闭动作的 ServiceRegistry 测试替身。 */
    private static final class RecordingServiceRegistry
            implements ServiceRegistry {

        private final List<String> events;
        private final boolean failUnregister;
        private int registerCount;

        private RecordingServiceRegistry(List<String> events) {
            this(events, false);
        }

        private RecordingServiceRegistry(
                List<String> events,
                boolean failUnregister
        ) {
            this.events = events;
            this.failUnregister = failUnregister;
        }

        @Override
        public void register(
                String serviceName,
                ServiceAddress address
        ) {
            registerCount++;
            events.add("registry-register");
        }

        @Override
        public void unregister(
                String serviceName,
                ServiceAddress address
        ) {
            events.add("registry-unregister");
            if (failUnregister) {
                throw new RpcException("模拟注销失败");
            }
        }

        @Override
        public void close() {
            events.add("registry-close");
        }
    }

    /**
     * 模拟网络服务端。
     *
     * <p>正常模式先记录“端口已绑定”，再执行上层回调；失败模式在回调之前
     * 抛出异常，用来证明绑定失败时不会发布地址。</p>
     */
    private static final class RecordingRpcServer implements RpcServer {

        private final List<String> events;
        private final boolean failBinding;
        private boolean stopped;

        private RecordingRpcServer(
                List<String> events,
                boolean failBinding
        ) {
            this.events = events;
            this.failBinding = failBinding;
        }

        @Override
        public void start(Runnable onStarted) {
            if (failBinding) {
                events.add("bind-failed");
                throw new RpcException("模拟端口绑定失败");
            }

            events.add("port-bound");
            onStarted.run();
        }

        @Override
        public void stop() {
            if (!stopped) {
                stopped = true;
                events.add("server-stop");
            }
        }
    }
}
