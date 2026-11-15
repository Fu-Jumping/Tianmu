package com.tianmu.agent.video.content;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.CallControl;

/** Live viewer authorization, separate from the platform processing grant. */
public interface ContentReadAuthority {
    record Identity(ContentAssetKey asset,long durationMs) {
        public Identity { java.util.Objects.requireNonNull(asset);if(durationMs<1)throw new IllegalArgumentException("Invalid content duration"); }
    }
    Identity current(long videoId,AccessScope viewer,CallControl control);
}
