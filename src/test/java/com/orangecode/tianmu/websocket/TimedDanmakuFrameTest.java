package com.orangecode.tianmu.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TimedDanmakuFrameTest {
    @Test void transfersTheFrameReferenceToTheBusinessHandler() {
        var channel = new EmbeddedChannel(new TimedDanmakuFrame.Stamp());
        var original = new TextWebSocketFrame(false, 1, "payload");
        try {
            assertTrue(channel.writeInbound(original));
            var stamped = assertInstanceOf(TimedDanmakuFrame.class, channel.readInbound());
            assertFalse(stamped.isFinalFragment());
            assertEquals(1, stamped.rsv());
            assertEquals("payload", stamped.text());
            assertSame(original.content(), stamped.content());
            assertEquals(1, stamped.refCnt());
            assertTrue(stamped.release());
            assertEquals(0, original.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test void releasesTheFrameWhenTheBusinessExecutorRejectsIt() {
        var context = mock(ChannelHandlerContext.class);
        when(context.fireChannelRead(any())).thenThrow(new RejectedExecutionException());
        var original = new TextWebSocketFrame("payload");
        assertThrows(RejectedExecutionException.class, () -> new TimedDanmakuFrame.Stamp().channelRead(context, original));
        assertEquals(0, original.refCnt());
    }
}
