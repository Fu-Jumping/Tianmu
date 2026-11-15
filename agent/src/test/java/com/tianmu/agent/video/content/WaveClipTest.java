package com.tianmu.agent.video.content;

import com.tianmu.agent.core.AgentException;
import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class WaveClipTest {
    @Test void normalizedAudioHasExactPhysicalPlaybackDuration(){
        assertDoesNotThrow(()->WaveClip.requireNormalized(TestWave.pcm(1000),1000));
        assertThrows(AgentException.class,()->WaveClip.requireNormalized(TestWave.pcm(1040),1000));
        assertThrows(AgentException.class,()->WaveClip.requireNormalized(TestWave.pcm(960),1000));
    }
    @Test void formatAndDeclaredRiffSizeCannotHideExtraAudio(){
        byte[] audio=TestWave.pcm(1000);ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).putInt(24,48000);
        assertThrows(AgentException.class,()->WaveClip.requireNormalized(audio,1000));
        byte[] truncated=java.util.Arrays.copyOf(TestWave.pcm(1000),100);
        assertThrows(AgentException.class,()->WaveClip.requireNormalized(truncated,1000));
        assertThrows(AgentException.class,()->WaveClip.requireNormalized(new byte[]{1,2,3},1000));
    }
    @Test void actualFfmpegFixtureMeetsTheSameContract()throws Exception {
        byte[] wave=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getProperty("basedir"),"../contracts/video-content/pcm-16k-1000ms.wav").normalize());
        assertDoesNotThrow(()->WaveClip.requireNormalized(wave,1000));
    }
}
