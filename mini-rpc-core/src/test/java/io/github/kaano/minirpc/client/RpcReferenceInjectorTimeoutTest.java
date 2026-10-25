package io.github.kaano.minirpc.client;

import io.github.kaano.minirpc.annotation.RpcReference;
import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 @RpcReference 的路由和超时配置能够从字段传到客户端工厂。 */
class RpcReferenceInjectorTimeoutTest {

    @Test
    void shouldInheritGlobalTimeoutByDefault() {
        GreetingService proxy = name -> "你好，" + name;
        RecordingProxyCreator proxyCreator =
                new RecordingProxyCreator(proxy);
        DefaultTimeoutBean target = new DefaultTimeoutBean();

        new RpcReferenceInjector(proxyCreator).inject(target);

        assertSame(proxy, target.greetingService);
        assertSame(GreetingService.class, proxyCreator.serviceInterface);
        assertEquals(ServiceKey.DEFAULT_GROUP, proxyCreator.group);
        assertEquals(ServiceKey.DEFAULT_VERSION, proxyCreator.version);
        assertNull(proxyCreator.requestTimeout);
        assertEquals(1, proxyCreator.invocationCount);
    }

    @Test
    void shouldCreateProxyWithReferenceTimeout() {
        GreetingService proxy = name -> "你好，" + name;
        RecordingProxyCreator proxyCreator =
                new RecordingProxyCreator(proxy);
        Duration timeout = Duration.ofMillis(750);
        CustomTimeoutBean target = new CustomTimeoutBean();

        new RpcReferenceInjector(proxyCreator).inject(target);

        assertSame(proxy, target.greetingService);
        assertSame(GreetingService.class, proxyCreator.serviceInterface);
        assertEquals("gray", proxyCreator.group);
        assertEquals("2.0", proxyCreator.version);
        assertEquals(timeout, proxyCreator.requestTimeout);
        assertEquals(1, proxyCreator.invocationCount);
    }

    @Test
    void shouldRejectNonPositiveReferenceTimeout() {
        RecordingProxyCreator proxyCreator =
                new RecordingProxyCreator(
                        (GreetingService) name -> "不会调用"
                );

        RpcException exception = assertThrows(
                RpcException.class,
                () -> new RpcReferenceInjector(proxyCreator)
                        .inject(new InvalidTimeoutBean())
        );

        assertTrue(exception.getMessage().contains("timeoutMillis 必须大于 0"));
        assertEquals(0, proxyCreator.invocationCount);
    }

    @Test
    void shouldRejectBlankGroupBeforeCreatingProxy() {
        RecordingProxyCreator proxyCreator =
                new RecordingProxyCreator(
                        (GreetingService) name -> "不会调用"
                );

        RpcException exception = assertThrows(
                RpcException.class,
                () -> new RpcReferenceInjector(proxyCreator)
                        .inject(new InvalidGroupBean())
        );

        assertTrue(exception.getMessage().contains("服务分组不能为空"));
        assertEquals(0, proxyCreator.invocationCount);
    }

    private interface GreetingService {
        String hello(String name);
    }

    private static final class DefaultTimeoutBean {
        @RpcReference
        private GreetingService greetingService;
    }

    private static final class CustomTimeoutBean {
        @RpcReference(
                group = "gray",
                version = "2.0",
                timeoutMillis = 750
        )
        private GreetingService greetingService;
    }

    private static final class InvalidTimeoutBean {
        @RpcReference(timeoutMillis = 0)
        private GreetingService greetingService;
    }

    private static final class InvalidGroupBean {
        @RpcReference(group = " ")
        private GreetingService greetingService;
    }

    /** 记录注入器传入的接口和超时，并返回预先准备好的代理对象。 */
    private static final class RecordingProxyCreator
            implements RpcReferenceInjector.ProxyCreator {

        private final Object proxy;
        private Class<?> serviceInterface;
        private String group;
        private String version;
        private Duration requestTimeout;
        private int invocationCount;

        private RecordingProxyCreator(Object proxy) {
            this.proxy = proxy;
        }

        @Override
        public Object createProxy(
                Class<?> serviceInterface,
                String group,
                String version,
                Duration requestTimeout
        ) {
            this.serviceInterface = serviceInterface;
            this.group = group;
            this.version = version;
            this.requestTimeout = requestTimeout;
            invocationCount++;
            return proxy;
        }
    }
}
