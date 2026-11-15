package com.tianmu.agent.video.content;

/** Receipts are internal. User reads require a separate live read grant and source guard. */
public interface ContentTranscriptStore {
    ContentWork.Receipt save(ContentWork.Job job,ContentWork.Lease lease,TimedTranscript transcript);
    void verify(ContentWork.Job job,ContentWork.Receipt receipt);
}
