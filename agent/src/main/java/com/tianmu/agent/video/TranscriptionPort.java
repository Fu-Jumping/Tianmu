package com.tianmu.agent.video;

import com.tianmu.agent.runtime.control.CallControl;

public interface TranscriptionPort {
    default String modelName(){return "";}
    String transcribe(String audioBase64, CallControl control);

    /** Legacy providers expose clip coverage only; never fabricate sentence timestamps. */
    default com.tianmu.agent.video.content.TimedTranscript transcribeTimed(String audioBase64,
            long startMs, long endMs, CallControl control) {
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("Invalid audio clip");
        control.checkpoint();
        String text = transcribe(audioBase64, control);
        control.checkpoint();
        return new com.tianmu.agent.video.content.TimedTranscript(startMs, endMs, text,
                text == null || text.isBlank() ? com.tianmu.agent.video.content.TimedTranscript.State.NO_TRANSCRIPT
                        : com.tianmu.agent.video.content.TimedTranscript.State.AVAILABLE,
                com.tianmu.agent.video.content.TimedTranscript.Precision.CLIP,
                text == null || text.isBlank() ? java.util.List.of()
                        : java.util.List.of(new com.tianmu.agent.video.content.TimedTranscript.Segment(startMs, endMs, text)));
    }
}
