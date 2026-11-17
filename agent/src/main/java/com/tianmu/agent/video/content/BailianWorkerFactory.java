package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;

/** Explicit assembly only; the host owns worker lifecycle and scheduling. */
public final class BailianWorkerFactory {
    private BailianWorkerFactory() {}
    public static ContentTranscriptionWorker create(MysqlContentWorkStore store,ContentWorkService service,
            ContentProcessingMediaPort media,ContentTranscriptStore results,String worker,String region,String apiKey,
            ObjectMapper json,Duration modelTimeout,Duration lease,Duration heartbeat) {
        return assemble(store,service,media,results,worker,
                BailianTranscriptionClient.create(region,apiKey,json,modelTimeout),lease,heartbeat);
    }
    static ContentTranscriptionWorker assemble(MysqlContentWorkStore store,ContentWorkService service,
            ContentProcessingMediaPort media,ContentTranscriptStore results,String worker,BailianTranscriptionClient client,
            Duration lease,Duration heartbeat) {
        return new ContentTranscriptionWorker(store,service,media,client,results,worker,
                TranscriptPreparation.STAGE_VERSION,lease,heartbeat);
    }
}
