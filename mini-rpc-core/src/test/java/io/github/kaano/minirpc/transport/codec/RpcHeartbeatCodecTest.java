package io.github.kaano.minirpc.transport.codec;

import io.github.kaano.minirpc.common.RpcHeartbeat;
import io.github.kaano.minirpc.common.RpcProtocol;
import io.github.kaano.minirpc.serialize.JdkSerializer;
import io.github.kaano.minirpc.serialize.JsonSerializer;
import io.github.kaano.minirpc.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证心跳能够复用现有协议头，并跨越 JSON、JDK 两种序列化边界。 */
class RpcHeartbeatCodecTest {

    @Test
    void shouldEncodeAndDecodePingWithJsonSerializer() {
        assertHeartbeatRoundTrip(
                new JsonSerializer(),
                RpcHeartbeat.ping(701L, 1_700_000_000_001L)
        );
    }

    @Test
    void shouldEncodeAndDecodePongWithJdkSerializer() {
        assertHeartbeatRoundTrip(
                new JdkSerializer(),
                RpcHeartbeat.pong(702L, 1_700_000_000_002L)
        );
    }

    private void assertHeartbeatRoundTrip(
            Serializer serializer,
            RpcHeartbeat expected
    ) {
        EmbeddedChannel encoderChannel = new EmbeddedChannel(
                new RpcEncoder(serializer)
        );
        EmbeddedChannel decoderChannel = new EmbeddedChannel(new RpcDecoder());

        try {
            assertTrue(encoderChannel.writeOutbound(expected));
            ByteBuf encoded = encoderChannel.readOutbound();

            // 消息类型位于固定协议头第 6 个偏移，必须写入预留的心跳类型 3。
            assertEquals(
                    RpcProtocol.MESSAGE_TYPE_HEARTBEAT,
                    encoded.getByte(encoded.readerIndex() + 6)
            );

            assertTrue(decoderChannel.writeInbound(encoded));
            RpcHeartbeat actual = decoderChannel.readInbound();
            assertEquals(expected, actual);
        } finally {
            encoderChannel.finishAndReleaseAll();
            decoderChannel.finishAndReleaseAll();
        }
    }
}
