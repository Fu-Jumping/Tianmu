package com.tianmu.agent.video.content;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Explicit admission for one already-authorized playback range; no automatic scheduling. */
public final class TranscriptPreparation {
    public static final String STAGE_VERSION="bailian-qwen-audio-3.0-asr-flash-wav16k-v1";
    private final ContentReadAuthority reader;
    private final HttpContentProcessingAuthority authority;
    private final ContentWorkService work;
    private final ContentRequestBinding binding;
    public TranscriptPreparation(ContentReadAuthority reader,HttpContentProcessingAuthority authority,ContentWorkService work,ContentRequestBinding binding) {
        this.reader=Objects.requireNonNull(reader);this.authority=Objects.requireNonNull(authority);this.work=Objects.requireNonNull(work);this.binding=Objects.requireNonNull(binding);
    }
    public ContentWork.Job admit(UUID preparationRequestId,long videoId,String playerVersion,long startMs,long endMs,long watchedUntilMs,
            AccessScope viewer,Instant deadline,CallControl control) {
        Objects.requireNonNull(preparationRequestId);Objects.requireNonNull(viewer);Objects.requireNonNull(deadline);Objects.requireNonNull(control);
        if(videoId<=0 || !viewer.readableVideoIds().contains(videoId))throw new AgentException("FORBIDDEN","Video outside viewer scope");
        if(playerVersion==null || playerVersion.isBlank() || startMs<0 || endMs<=startMs || endMs-startMs>30000
                || watchedUntilMs<endMs || !deadline.isAfter(Instant.now()) || deadline.isAfter(control.deadline()))
            throw new AgentException("INVALID_CONTENT_REQUEST","Invalid playback range or deadline");
        control.checkpoint();
        var identity=reader.current(videoId,viewer,control);
        if(identity==null || identity.asset().videoId()!=videoId || !identity.asset().tenantId().equals(viewer.tenantId())
                || !"public".equals(identity.asset().authorizationDomain()))throw new AgentException("FORBIDDEN","Video read authority unavailable");
        if(!identity.asset().sourceVersion().equals(playerVersion))throw new AgentException("VIDEO_VERSION_CHANGED","Player media version changed");
        if(endMs>identity.durationMs())throw new AgentException("INVALID_CONTENT_REQUEST","Playback range exceeds video");
        var unit=new ContentWork.Unit(identity.asset(),ContentWork.Stage.TRANSCRIBE,"clip-"+startMs+"-"+endMs,STAGE_VERSION,startMs,endMs);
        binding.bind(preparationRequestId,viewer,unit,watchedUntilMs);
        control.checkpoint();
        var grant=authority.issue(preparationRequestId,videoId,control);
        if(!identity.asset().equals(grant.asset()))throw new AgentException("VIDEO_VERSION_CHANGED","Video authority changed during preparation");
        if(deadline.isAfter(grant.expiresAt()))throw new AgentException("CONTENT_DEADLINE_EXCEEDS_GRANT","Processing deadline exceeds the media grant validity");
        return work.admit(unit,grant,deadline,2,control);
    }
}
