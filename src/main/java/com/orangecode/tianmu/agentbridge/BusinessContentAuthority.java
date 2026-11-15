package com.orangecode.tianmu.agentbridge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;

/** Opaque platform grants for public media only. Viewer credentials are never accepted here. */
public final class BusinessContentAuthority {
    public record Asset(String tenantId,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) long videoId,String sourceVersion,String authorizationDomain,long revocationEpoch) {}
    public record Grant(UUID id,Asset asset,Instant expiresAt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final BusinessVideoContent media;
    private final String tenant;
    private final int ttlSeconds;
    public BusinessContentAuthority(DataSource source,BusinessVideoContent media,String tenant,int ttlSeconds,int querySeconds) {
        if(tenant==null || !tenant.matches("[A-Za-z0-9_-]{1,64}") || ttlSeconds<30 || ttlSeconds>3600 || querySeconds<1 || querySeconds>10)
            throw new IllegalArgumentException("Invalid content authority policy");
        this.db=new JdbcTemplate(source);db.setQueryTimeout(querySeconds);
        this.tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(querySeconds);
        this.media=Objects.requireNonNull(media);this.tenant=tenant;this.ttlSeconds=ttlSeconds;
    }
    public Grant issue(UUID requestId,long videoId) {
        if(requestId==null || videoId<=0)throw new BridgeException("INVALID_CONTENT_REQUEST",400);
        requireSchema();
        Grant existing=find(requestId);
        if(existing!=null) {
            if(existing.asset().videoId()!=videoId)throw new BridgeException("CONTENT_GRANT_CONFLICT",409);
            require(existing);return existing;
        }
        BusinessVideoContent.Source source=media.publicSource(videoId);
        try {
            tx.executeWithoutResult(ignored->{
                if(db.queryForList("SELECT video_id FROM video WHERE video_id=? AND status=2 AND is_delete=0 FOR UPDATE",videoId).isEmpty())
                    throw new BridgeException("FORBIDDEN",403);
                var epochs=db.queryForList("SELECT epoch FROM agent_media_authority_epoch WHERE video_id=? FOR UPDATE",videoId);
                if(epochs.size()!=1)throw new BridgeException("CONTENT_AUTHORITY_NOT_READY",503);
                long epoch=((Number)epochs.get(0).get("epoch")).longValue();
                db.update("INSERT INTO agent_media_processing_grant(grant_id,tenant_id,video_id,source_version,revocation_epoch,purpose,expires_at) VALUES(?,?,?,?,?,'VIDEO_CONTENT_PREPARATION',TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)))",
                        requestId.toString(),tenant,videoId,source.sourceVersion(),epoch,ttlSeconds);
            });
        }catch(DuplicateKeyException race){ /* Reconcile the same idempotent grant request below. */ }
        Grant issued=find(requestId);
        if(issued==null || issued.asset().videoId()!=videoId)throw new BridgeException("CONTENT_GRANT_CONFLICT",409);
        require(issued);return issued;
    }
    private Grant find(UUID id) {
        var rows=db.query("SELECT * FROM agent_media_processing_grant WHERE grant_id=? AND tenant_id=?",
                (r,n)->new Grant(UUID.fromString(r.getString("grant_id")),
                        new Asset(r.getString("tenant_id"),r.getLong("video_id"),r.getString("source_version"),"public",r.getLong("revocation_epoch")),
                        r.getObject("expires_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id.toString(),tenant);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void require(Grant supplied) {
        if(supplied==null || supplied.id()==null || supplied.asset()==null || supplied.expiresAt()==null)
            throw new BridgeException("CONTENT_GRANT_REVOKED",403);
        requireSchema();
        Grant stored=find(supplied.id());
        if(!supplied.equals(stored))throw new BridgeException("CONTENT_GRANT_REVOKED",403);
        requireEpoch(stored);
        if(!media.publicSource(stored.asset().videoId()).sourceVersion().equals(stored.asset().sourceVersion()))
            throw new BridgeException("VIDEO_VERSION_CHANGED",409);
        requireEpoch(stored);
    }
    private void requireEpoch(Grant grant) {
        Integer count=db.queryForObject("SELECT COUNT(*) FROM agent_media_processing_grant g JOIN agent_media_authority_epoch e ON e.video_id=g.video_id JOIN video v ON v.video_id=g.video_id WHERE g.grant_id=? AND g.tenant_id=? AND g.revoked_at IS NULL AND g.expires_at>UTC_TIMESTAMP(6) AND g.purpose='VIDEO_CONTENT_PREPARATION' AND e.epoch=g.revocation_epoch AND v.status=2 AND v.is_delete=0",
                Integer.class,grant.id().toString(),tenant);
        if(count==null || count!=1)throw new BridgeException("CONTENT_GRANT_REVOKED",403);
    }
    public BusinessVideoContent.Media read(Grant grant,long startMs,long endMs) {
        require(grant);
        var result=media.publicSample(grant.asset().videoId(),grant.asset().sourceVersion(),startMs,endMs);
        require(grant);return result;
    }
    public void revoke(UUID id) {
        db.update("UPDATE agent_media_processing_grant SET revoked_at=UTC_TIMESTAMP(6) WHERE grant_id=? AND tenant_id=? AND revoked_at IS NULL",id.toString(),tenant);
    }
    private void requireSchema() {
        try {
            Integer ready=db.queryForObject("SELECT COUNT(*) FROM agent_media_authority_schema WHERE schema_id='content-processing-v1' AND ready=TRUE",Integer.class);
            if(ready==null || ready!=1)throw new BridgeException("CONTENT_AUTHORITY_NOT_READY",503);
        }catch(org.springframework.dao.DataAccessException missing){throw new BridgeException("CONTENT_AUTHORITY_NOT_READY",503);}
    }
}
