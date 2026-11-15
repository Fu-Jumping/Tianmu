package com.orangecode.tianmu.agentbridge;

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="CONTENT_AUTHORITY_IT",matches="true")
class BusinessContentAuthorityIntegrationTest {
    private static HikariDataSource source;
    private JdbcTemplate db;
    private BusinessVideoContent media;
    private BusinessContentAuthority authority;
    private static final String VERSION="a".repeat(64);
    @BeforeAll static void open()throws Exception {
        String url=System.getenv("AGENT_CONTENT_JDBC_URL");
        assertNotNull(url);assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/video_content_jobs_it(?:\\?.*)?"));
        var c=new HikariConfig();c.setJdbcUrl(url);c.setUsername("root");c.setPassword(System.getenv().getOrDefault("AGENT_CONTENT_JDBC_PASSWORD",""));
        c.setMaximumPoolSize(8);source=new HikariDataSource(c);
        var db=new JdbcTemplate(source);
        db.execute("CREATE TABLE IF NOT EXISTS video(video_id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,status INT NOT NULL,is_delete INT NOT NULL,file_url VARCHAR(200),duration DOUBLE,title VARCHAR(200)) ENGINE=InnoDB");
        for(String trigger:List.of("agent_media_epoch_insert","agent_media_epoch_update","agent_media_epoch_delete"))db.execute("DROP TRIGGER IF EXISTS "+trigger);
        for(String table:List.of("agent_media_processing_grant","agent_media_authority_epoch","agent_media_authority_schema"))db.execute("DROP TABLE IF EXISTS "+table);
        try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource("deploy/site/migrations/20261115_content_processing_authority.sql"));}
    }
    @AfterAll static void close(){if(source!=null)source.close();}
    @BeforeEach void reset(){
        db=new JdbcTemplate(source);db.update("DELETE FROM agent_media_processing_grant");db.update("DELETE FROM video");db.update("DELETE FROM agent_media_authority_epoch");
        db.update("INSERT INTO video(video_id,user_id,status,is_delete,file_url,duration,title) VALUES(7,1,2,0,'sample.mp4',60,'course')");
        media=mock(BusinessVideoContent.class);
        when(media.publicSource(7)).thenReturn(new BusinessVideoContent.Source(7,VERSION,60000));
        authority=new BusinessContentAuthority(source,media,"site",900,5);
    }
    @Test void grantIsDurableIdempotentAndCannotBeForged(){
        UUID id=UUID.randomUUID();var grant=authority.issue(id,7);
        assertEquals(grant,authority.issue(id,7));
        var restarted=new BusinessContentAuthority(source,media,"site",900,5);restarted.require(grant);
        assertEquals(grant.id(),id);
        assertThrows(BridgeException.class,()->authority.require(new BusinessContentAuthority.Grant(grant.id(),grant.asset(),grant.expiresAt().plusSeconds(1))));
        assertThrows(BridgeException.class,()->authority.issue(id,8));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_media_processing_grant",Integer.class));
    }
    @Test void unpublishThenRepublishNeverRevivesOldGrant(){
        var old=authority.issue(UUID.randomUUID(),7);
        db.update("UPDATE video SET status=0 WHERE video_id=7");
        assertThrows(BridgeException.class,()->authority.require(old));
        db.update("UPDATE video SET status=2 WHERE video_id=7");
        assertThrows(BridgeException.class,()->authority.require(old));
        var fresh=authority.issue(UUID.randomUUID(),7);
        assertTrue(fresh.asset().revocationEpoch()>old.asset().revocationEpoch());
        authority.require(fresh);
    }
    @Test void deletionAndIdReuseDoNotReviveOldGrant(){
        var old=authority.issue(UUID.randomUUID(),7);
        db.update("DELETE FROM video WHERE video_id=7");
        db.update("INSERT INTO video(video_id,user_id,status,is_delete,file_url,duration) VALUES(7,1,2,0,'sample.mp4',60)");
        assertThrows(BridgeException.class,()->authority.require(old));
        assertTrue(authority.issue(UUID.randomUUID(),7).asset().revocationEpoch()>old.asset().revocationEpoch());
    }
    @Test void metadataUpdatesDoNotRevokeButMediaAndOwnershipChangesDo(){
        var grant=authority.issue(UUID.randomUUID(),7);
        db.update("UPDATE video SET title='new title' WHERE video_id=7");authority.require(grant);
        db.update("UPDATE video SET duration=61 WHERE video_id=7");assertThrows(BridgeException.class,()->authority.require(grant));
        var next=authority.issue(UUID.randomUUID(),7);
        db.update("UPDATE video SET user_id=2 WHERE video_id=7");assertThrows(BridgeException.class,()->authority.require(next));
    }
    @Test void versionChangeAndExplicitRevocationRejectMediaReads(){
        var grant=authority.issue(UUID.randomUUID(),7);
        when(media.publicSource(7)).thenReturn(new BusinessVideoContent.Source(7,"b".repeat(64),60000));
        assertEquals("VIDEO_VERSION_CHANGED",assertThrows(BridgeException.class,()->authority.require(grant)).code());
        verify(media,never()).publicSample(anyLong(),anyString(),anyLong(),anyLong());
        when(media.publicSource(7)).thenReturn(new BusinessVideoContent.Source(7,VERSION,60000));
        authority.revoke(grant.id());assertThrows(BridgeException.class,()->authority.read(grant,0,1000));
        verify(media,never()).publicSample(anyLong(),anyString(),anyLong(),anyLong());
    }
    @Test void revocationDuringExtractionRejectsTheReturnedMedia(){
        var grant=authority.issue(UUID.randomUUID(),7);
        when(media.publicSample(7,VERSION,0,1000)).thenAnswer(call->{db.update("UPDATE video SET is_delete=1 WHERE video_id=7");return new BusinessVideoContent.Media(new BusinessVideoContent.Source(7,VERSION,60000),0,1000,List.of(),"audio");});
        assertThrows(BridgeException.class,()->authority.read(grant,0,1000));
    }
    @Test void privateOrExpiredMediaCannotReceiveOrUsePlatformGrant(){
        db.update("UPDATE video SET status=1 WHERE video_id=7");
        assertThrows(BridgeException.class,()->authority.issue(UUID.randomUUID(),7));
        db.update("UPDATE video SET status=2 WHERE video_id=7");
        var grant=authority.issue(UUID.randomUUID(),7);
        db.update("UPDATE agent_media_processing_grant SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE grant_id=?",grant.id().toString());
        assertThrows(BridgeException.class,()->authority.require(grant));
    }
    @Test void concurrentIssuerRequestsReturnOneDurableGrant()throws Exception {
        UUID id=UUID.randomUUID();var pool=Executors.newFixedThreadPool(4);
        try {
            var calls=new ArrayList<Future<BusinessContentAuthority.Grant>>();
            for(int i=0;i<4;i++)calls.add(pool.submit(()->authority.issue(id,7)));
            var first=calls.get(0).get(15,TimeUnit.SECONDS);
            for(var call:calls)assertEquals(first,call.get(15,TimeUnit.SECONDS));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_media_processing_grant",Integer.class));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void incompleteAuthorityInstallationStopsExistingAndNewGrants(){
        var grant=authority.issue(UUID.randomUUID(),7);
        db.update("UPDATE agent_media_authority_schema SET ready=FALSE");
        try {
            assertEquals("CONTENT_AUTHORITY_NOT_READY",assertThrows(BridgeException.class,()->authority.require(grant)).code());
            assertEquals("CONTENT_AUTHORITY_NOT_READY",assertThrows(BridgeException.class,()->authority.issue(UUID.randomUUID(),7)).code());
        }finally{db.update("UPDATE agent_media_authority_schema SET ready=TRUE");}
    }
    @Test void viewerReadIdentityUsesLiveEpochAndNeverIssuesAProcessingGrant(){
        var viewer=new BusinessAuthentication.Principal("site","reader");
        var reader=new BusinessContentIdentityReader(source,media,"site",5);
        var identity=new BusinessContentIdentityReader(source,media,"site",5).readableIdentity(viewer,7);assertEquals(VERSION,identity.asset().sourceVersion());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_media_processing_grant",Integer.class));
        db.update("UPDATE video SET status=0 WHERE video_id=7");
        assertThrows(BridgeException.class,()->new BusinessContentIdentityReader(source,media,"site",5).readableIdentity(viewer,7));
        db.update("UPDATE video SET status=2 WHERE video_id=7");
        assertTrue(new BusinessContentIdentityReader(source,media,"site",5).readableIdentity(viewer,7).asset().revocationEpoch()>identity.asset().revocationEpoch());
        assertThrows(BridgeException.class,()->new BusinessContentIdentityReader(source,media,"site",5).readableIdentity(new BusinessAuthentication.Principal("other","reader"),7));
    }
    @Test void epochChangeDuringReadMetadataDoesNotCreateAMixedIdentity(){
        when(media.publicSource(7)).thenAnswer(call->{db.update("UPDATE video SET status=0 WHERE video_id=7");db.update("UPDATE video SET status=2 WHERE video_id=7");return new BusinessVideoContent.Source(7,VERSION,60000);});
        assertEquals("VIDEO_VERSION_CHANGED",assertThrows(BridgeException.class,()->new BusinessContentIdentityReader(source,media,"site",5).readableIdentity(new BusinessAuthentication.Principal("site","reader"),7)).code());
    }
    @Test void audioOnlyPlatformReadDoesNotInvokeFrameExtraction(){
        var grant=authority.issue(UUID.randomUUID(),7);
        var audio=new BusinessVideoContent.Media(new BusinessVideoContent.Source(7,VERSION,60000),0,1000,List.of(),"AQID");
        when(media.publicAudioSample(7,VERSION,0,1000)).thenReturn(audio);
        assertEquals(audio,authority.readAudio(grant,0,1000));
        verify(media,never()).publicSample(anyLong(),anyString(),anyLong(),anyLong());
    }
}
