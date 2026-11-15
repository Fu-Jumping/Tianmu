package com.tianmu.agent.video.content;

import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.runtime.control.CallControl;

/** Platform media access has its own authority, separate from viewer-scoped VideoContentPort. */
public interface ContentProcessingMediaPort {
    VideoContent.Media media(ContentWork.Grant grant,long startMs,long endMs,CallControl control);
    default VideoContent.Media audio(ContentWork.Grant grant,long startMs,long endMs,CallControl control){return media(grant,startMs,endMs,control);}
}
