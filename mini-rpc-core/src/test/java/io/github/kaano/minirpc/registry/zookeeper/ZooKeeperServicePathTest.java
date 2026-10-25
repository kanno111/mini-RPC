package io.github.kaano.minirpc.registry.zookeeper;

import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.registry.ServiceAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证服务身份和地址中的特殊字符不会破坏 ZooKeeper 路径层级。 */
class ZooKeeperServicePathTest {

    @Test
    void shouldEncodeEveryDynamicPathSegment() {
        ServiceKey serviceKey = new ServiceKey(
                "com.example.PaymentService",
                "gray/canary",
                "2.0 beta"
        );
        ServiceAddress address = new ServiceAddress("2001:db8::1", 8080);

        String servicePath = ZooKeeperServiceRegistry.buildServicePath(
                serviceKey
        );
        String providerPath = ZooKeeperServiceRegistry.buildProviderPath(
                serviceKey,
                address
        );

        assertTrue(servicePath.contains("/gray%2Fcanary/"));
        assertTrue(servicePath.contains("/2.0%20beta/providers"));
        assertFalse(servicePath.contains("/gray/canary/"));
        assertTrue(providerPath.endsWith("2001%3Adb8%3A%3A1%3A8080"));
    }

    @Test
    void shouldRestoreEncodedProviderAddress() {
        ServiceAddress address = ZooKeeperServicePath.decodeProviderNode(
                "2001%3Adb8%3A%3A1%3A8080"
        );

        assertEquals(new ServiceAddress("2001:db8::1", 8080), address);
    }
}
