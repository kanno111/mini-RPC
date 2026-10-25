package io.github.kaano.minirpc.example.provider;

import org.springframework.stereotype.Component;

/**
 * 一个普通的 Spring Bean，用来证明 RPC 服务实现可以使用 Spring 依赖注入。
 *
 * <p>它不属于 RPC 框架，只是 Provider 示例自己的业务依赖。</p>
 */
@Component
public class ProviderIdentity {

    /** 当前 Provider Java 进程的进程 ID。 */
    private final long processId = ProcessHandle.current().pid();

    public long getProcessId() {
        return processId;
    }
}
