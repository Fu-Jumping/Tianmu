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
    /** Background owner revalidation uses the primary database, not the authentication read replica. */
    public BusinessAuthentication.Principal activeViewer(String requestedTenant,String user,java.util.Set<String> activeStates) {
        if(!tenant.equals(requestedTenant) || user==null || !user.matches("[1-9][0-9]{0,18}") || activeStates==null || activeStates.isEmpty())
            throw new BridgeException("FORBIDDEN",403);
        try {
            var rows=db.queryForList("SELECT state FROM user WHERE user_id=? AND is_delete=0",Long.parseLong(user));
            if(rows.size()!=1 || !activeStates.contains(String.valueOf(rows.get(0).get("state"))))throw new BridgeException("FORBIDDEN",403);
            return new BusinessAuthentication.Principal(tenant,user);
        } catch(BridgeException denied){throw denied;}
        catch(Exception unavailable){throw new BridgeException("BUSINESS_UNAVAILABLE",503);}
    }
    private long publicEpoch(long videoId) {
        var rows=db.queryForList("SELECT e.epoch FROM agent_media_authority_epoch e JOIN video v ON v.video_id=e.video_id WHERE v.video_id=? AND v.status=2 AND v.is_delete=0",videoId);
        if(rows.size()!=1)throw new BridgeException("FORBIDDEN",403);
        return ((Number)rows.get(0).get("epoch")).longValue();
    }
}
