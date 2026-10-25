package io.github.kaano.minirpc.loadbalance;

import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.registry.ServiceAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证同一接口的不同版本拥有彼此独立的轮询状态。 */
class RoundRobinLoadBalanceServiceKeyTest {

    @Test
    void shouldKeepRoundRobinCountersIndependentByServiceKey() {
        RoundRobinLoadBalance loadBalance = new RoundRobinLoadBalance();
        List<ServiceAddress> addresses = List.of(
                new ServiceAddress("127.0.0.1", 8080),
                new ServiceAddress("127.0.0.1", 8081)
        );
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

        assertEquals(addresses.get(0), loadBalance.select(versionOne, addresses));
        assertEquals(addresses.get(1), loadBalance.select(versionOne, addresses));

        // 2.0 第一次调用应从自己的下标 0 开始，而不是继承 1.0 的计数器。
        assertEquals(addresses.get(0), loadBalance.select(versionTwo, addresses));
    }
}
