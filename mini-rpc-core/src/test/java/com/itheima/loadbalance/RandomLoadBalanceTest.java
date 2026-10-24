package com.itheima.loadbalance;

import com.itheima.common.RpcException;
import com.itheima.registry.ServiceAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证随机负载均衡只会返回候选地址，并能尽早拒绝无效输入。 */
class RandomLoadBalanceTest {

    private final RandomLoadBalance loadBalance =
            new RandomLoadBalance();

    @Test
    void shouldReturnTheOnlyAvailableAddress() {
        ServiceAddress address = new ServiceAddress("127.0.0.1", 8080);

        /*
         * 只有一个候选地址时，随机下标必然为 0。这个断言没有概率因素，
         * 因此测试不会偶发失败，同时也能验证返回的是原列表中的对象。
         */
        ServiceAddress selected = loadBalance.select(
                "com.example.HelloService",
                List.of(address)
        );

        assertSame(address, selected);
    }

    @Test
    void shouldRejectEmptyAddressList() {
        assertThrows(
                RpcException.class,
                () -> loadBalance.select(
                        "com.example.HelloService",
                        List.of()
                )
        );
    }
}
