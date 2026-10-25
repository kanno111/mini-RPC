package io.github.kaano.minirpc.spring.autoconfigure;

import io.github.kaano.minirpc.annotation.RpcService;
import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.server.RpcProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.TimeUnit;

/**
 * 把原本手动启动的 {@link RpcProvider} 接入 Spring 容器生命周期。
 *
 * <p>Spring 启动完成时调用 {@link #start()}，关闭容器时调用 {@link #stop()}。
 * Provider 的 Netty start 方法会阻塞等待端口关闭，所以这里使用独立线程运行，
 * 不能阻塞 Spring 的主启动线程。</p>
 */
public final class SpringRpcProviderLifecycle implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            SpringRpcProviderLifecycle.class
    );

    /** 允许后台启动线程退出的最长等待时间。 */
    private static final long STOP_WAIT_SECONDS = 5;

    private final RpcProvider rpcProvider;
    private final ListableBeanFactory beanFactory;

    /** 执行阻塞式 RpcProvider.start() 的后台线程。 */
    private volatile Thread providerThread;

    /** SmartLifecycle 用它回答当前组件是否正在运行。 */
    private volatile boolean running;

    public SpringRpcProviderLifecycle(
            RpcProvider rpcProvider,
            ListableBeanFactory beanFactory
    ) {
        this.rpcProvider = rpcProvider;
        this.beanFactory = beanFactory;
    }

    /**
     * 注册 Spring 创建的服务对象，然后在后台线程启动 Netty。
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        String[] serviceBeanNames = beanFactory.getBeanNamesForAnnotation(
                RpcService.class
        );
        if (serviceBeanNames.length == 0) {
            throw new RpcException(
                    "Provider 已启用，但 Spring 容器中没有 @RpcService 服务"
            );
        }

        for (String beanName : serviceBeanNames) {
            RpcService annotation = beanFactory.findAnnotationOnBean(
                    beanName,
                    RpcService.class
            );
            if (annotation == null) {
                throw new RpcException(
                        "无法读取 RPC 服务注解，Bean：" + beanName
                );
            }

            Object serviceBean = beanFactory.getBean(beanName);
            registerService(
                    annotation.serviceInterface(),
                    annotation.group(),
                    annotation.version(),
                    serviceBean
            );
        }

        running = true;
        providerThread = Thread.ofPlatform()
                .name("mini-rpc-provider-server")
                .unstarted(this::runProvider);
        providerThread.start();
    }

    /** 在后台执行阻塞式服务端，并记录意外退出。 */
    private void runProvider() {
        try {
            rpcProvider.start();
        } catch (RuntimeException exception) {
            if (running) {
                LOGGER.error("Spring 管理的 RPC Provider 异常退出", exception);
            }
        } finally {
            running = false;
        }
    }

    /**
     * 将 Class<?> 和 Object 安全转换为 RpcProvider 泛型注册方法需要的类型。
     */
    private <T> void registerService(
            Class<T> serviceInterface,
            String group,
            String version,
            Object serviceBean
    ) {
        rpcProvider.registerService(
                serviceInterface,
                group,
                version,
                serviceInterface.cast(serviceBean)
        );
    }

    /** 关闭 Netty、ZooKeeper，并等待后台线程结束。 */
    @Override
    public synchronized void stop() {
        if (!running && providerThread == null) {
            return;
        }

        running = false;
        rpcProvider.close();

        Thread threadToWait = providerThread;
        providerThread = null;
        if (threadToWait == null || threadToWait == Thread.currentThread()) {
            return;
        }

        try {
            threadToWait.join(TimeUnit.SECONDS.toMillis(STOP_WAIT_SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RpcException("等待 RPC Provider 停止时线程被中断", exception);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
