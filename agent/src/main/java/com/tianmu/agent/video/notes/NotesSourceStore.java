package com.tianmu.agent.video.notes;

import com.tianmu.agent.video.content.ContentWork;

/** Private immutable evidence; reading this store is not end-user authorization. */
public interface NotesSourceStore {
    ContentWork.Receipt save(ContentWork.Job job,ContentWork.Lease lease,NotesSourceProcessor.Evidence evidence);
    NotesSourceProcessor.Evidence read(ContentWork.Job job,ContentWork.Receipt receipt);
}
