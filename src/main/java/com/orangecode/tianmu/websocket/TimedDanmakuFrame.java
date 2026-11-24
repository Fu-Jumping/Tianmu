package com.orangecode.tianmu.websocket;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.concurrent.RejectedExecutionException;

/** Transfers the inbound frame reference and marks the handoff to the business executor. */
final class TimedDanmakuFrame extends TextWebSocketFrame {
    final long queuedAt = System.nanoTime();

    private TimedDanmakuFrame(TextWebSocketFrame source) {
        super(source.isFinalFragment(), source.rsv(), source.content());
    }

    @ChannelHandler.Sharable
    static final class Stamp extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (!(message instanceof TextWebSocketFrame frame)) {
                ctx.fireChannelRead(message);
                return;
            }
            var timed = new TimedDanmakuFrame(frame);
            try {
                ctx.fireChannelRead(timed);
            } catch (RejectedExecutionException full) {
                timed.release();
                throw full;
            }
        }
    }
}
