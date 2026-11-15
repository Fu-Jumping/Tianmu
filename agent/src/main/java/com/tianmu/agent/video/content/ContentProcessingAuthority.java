package com.tianmu.agent.video.content;

import com.tianmu.agent.runtime.control.CallControl;

/** The media business service must check version, purpose, expiry and revocation at each checkpoint.
 * No implementation may turn the initiating viewer's credentials into a platform grant.
 */
public interface ContentProcessingAuthority {
    void require(ContentWork.Grant grant, ContentWork.Checkpoint checkpoint, CallControl control);
}
