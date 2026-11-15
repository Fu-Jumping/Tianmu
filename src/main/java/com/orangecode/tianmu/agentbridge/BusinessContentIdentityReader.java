package com.orangecode.tianmu.agentbridge;

import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** Read-only authority; remains available when platform content production is paused. */
public final class BusinessContentIdentityReader {
    public record Identity(BusinessContentAuthority.Asset asset,long durationMs) {}
    private final JdbcTemplate db;
    private final BusinessVideoContent media;
    private final String tenant;
    public BusinessContentIdentityReader(DataSource source,BusinessVideoContent media,String tenant,int querySeconds){
        if(tenant==null || !tenant.matches("[A-Za-z0-9_-]{1,64}") || querySeconds<1 || querySeconds>10)throw new IllegalArgumentException("Invalid content read authority");
        this.db=new JdbcTemplate(source);db.setQueryTimeout(querySeconds);this.media=java.util.Objects.requireNonNull(media);this.tenant=tenant;
    }
    public Identity readableIdentity(BusinessAuthentication.Principal viewer,long videoId) {
        if(viewer==null || !tenant.equals(viewer.tenantId()) || videoId<=0)throw new BridgeException("FORBIDDEN",403);
        try {
            Integer ready=db.queryForObject("SELECT COUNT(*) FROM agent_media_authority_schema WHERE schema_id='content-processing-v1' AND ready=TRUE",Integer.class);
            if(ready==null || ready!=1)throw new BridgeException("CONTENT_AUTHORITY_NOT_READY",503);
        }catch(org.springframework.dao.DataAccessException missing){throw new BridgeException("CONTENT_AUTHORITY_NOT_READY",503);}
        long before=publicEpoch(videoId);var source=media.publicSource(videoId);
        if(before!=publicEpoch(videoId))throw new BridgeException("VIDEO_VERSION_CHANGED",409);
        return new Identity(new BusinessContentAuthority.Asset(tenant,videoId,source.sourceVersion(),"public",before),source.durationMs());
    }
    private long publicEpoch(long videoId) {
        var rows=db.queryForList("SELECT e.epoch FROM agent_media_authority_epoch e JOIN video v ON v.video_id=e.video_id WHERE v.video_id=? AND v.status=2 AND v.is_delete=0",videoId);
        if(rows.size()!=1)throw new BridgeException("FORBIDDEN",403);
        return ((Number)rows.get(0).get("epoch")).longValue();
    }
}
