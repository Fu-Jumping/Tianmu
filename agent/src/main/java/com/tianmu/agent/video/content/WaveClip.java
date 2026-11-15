package com.tianmu.agent.video.content;

import com.tianmu.agent.core.AgentException;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** The processing bridge contract is exactly mono 16 kHz signed 16-bit PCM for the declared range. */
final class WaveClip {
    private WaveClip() {}
    static void requireNormalized(byte[] audio,long durationMs) {
        try {
            if(audio==null || audio.length<44 || audio.length>4_000_000 || durationMs<=0 || durationMs>120000)throw new IllegalArgumentException();
            ByteBuffer b=ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
            if(!"RIFF".equals(id(audio,0)) || !"WAVE".equals(id(audio,8)) || Integer.toUnsignedLong(b.getInt(4))+8!=audio.length)throw new IllegalArgumentException();
            int cursor=12;boolean format=false,data=false;
            while(cursor<audio.length) {
                if(audio.length-cursor<8)throw new IllegalArgumentException();
                String kind=id(audio,cursor);long size=Integer.toUnsignedLong(b.getInt(cursor+4));
                long next=cursor+8L+size+(size&1);
                if(next>audio.length)throw new IllegalArgumentException();int start=cursor+8;
                if("fmt ".equals(kind)) {
                    if(format || data || size<16 || Short.toUnsignedInt(b.getShort(start))!=1 || b.getShort(start+2)!=1
                            || b.getInt(start+4)!=16000 || b.getInt(start+8)!=32000 || b.getShort(start+12)!=2 || b.getShort(start+14)!=16)
                        throw new IllegalArgumentException();
                    format=true;
                }else if("data".equals(kind)) {
                    if(!format || data || size!=durationMs*32)throw new IllegalArgumentException();data=true;
                }
                cursor=(int)next;
            }
            if(!format || !data)throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new AgentException("CONTENT_AUDIO_UNAVAILABLE","Audio format or physical duration differs from the declared playback range");}
    }
    private static String id(byte[] bytes,int offset){return new String(bytes,offset,4,StandardCharsets.US_ASCII);}
}
