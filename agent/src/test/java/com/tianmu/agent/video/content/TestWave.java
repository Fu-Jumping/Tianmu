package com.tianmu.agent.video.content;

import java.nio.*;
import java.nio.charset.StandardCharsets;

final class TestWave {
    static byte[] pcm(long milliseconds) {
        int bytes=Math.toIntExact(milliseconds*32);ByteBuffer b=ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+bytes).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);return b.array();
    }
}
