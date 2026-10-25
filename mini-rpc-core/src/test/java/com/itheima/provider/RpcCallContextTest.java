package com.itheima.provider;

import com.itheima.common.RpcException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Provider 执行中 deadline 检查及 ThreadLocal 清理。 */
class RpcCallContextTest {

    @Test
    void shouldRejectAtDeadlineAndClearContextAfterScope() {
        assertFalse(RpcCallContext.hasContext());

        try (RpcCallContext.Scope ignored = RpcCallContext.bind(401L, 1_000L)) {
            assertTrue(RpcCallContext.hasContext());
            assertEquals(401L, RpcCallContext.getRequestId());
            assertEquals(1_000L, RpcCallContext.getDeadlineEpochMillis());
            assertFalse(RpcCallContext.isDeadlineExceeded(999L));
            assertTrue(RpcCallContext.isDeadlineExceeded(1_000L));

            RpcException exception = assertThrows(
                    RpcException.class,
                    () -> RpcCallContext.checkDeadline(1_000L)
            );
            assertTrue(exception.getMessage().contains("执行期间已超过 deadline"));
            assertTrue(exception.getMessage().contains("401"));
        }

        assertFalse(RpcCallContext.hasContext());
        assertThrows(RpcException.class, RpcCallContext::getRequestId);
    }

    @Test
    void shouldTreatZeroDeadlineAsNoLimit() {
        try (RpcCallContext.Scope ignored = RpcCallContext.bind(402L, 0L)) {
            assertFalse(RpcCallContext.isDeadlineExceeded(Long.MAX_VALUE));
            RpcCallContext.checkDeadline(Long.MAX_VALUE);
        }
    }

    @Test
    void shouldRestoreOuterContextAfterNestedScope() {
        try (RpcCallContext.Scope outer = RpcCallContext.bind(403L, 2_000L)) {
            try (RpcCallContext.Scope inner = RpcCallContext.bind(404L, 3_000L)) {
                assertEquals(404L, RpcCallContext.getRequestId());
            }

            assertEquals(403L, RpcCallContext.getRequestId());
            assertEquals(2_000L, RpcCallContext.getDeadlineEpochMillis());
        }

        assertFalse(RpcCallContext.hasContext());
    }
}
