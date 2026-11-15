package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.*;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.*;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.video.TranscriptionPort;
import static com.tianmu.agent.video.content.ContentWork.*;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated disposable database only; never connects to the business or production Agent database. */
@EnabledIfEnvironmentVariable(named="AGENT_CONTENT_IT", matches="true")
class MysqlContentWorkIntegrationTest {
    private static HikariDataSource source;
    private MysqlContentWorkStore store;
    private JdbcTemplate db;
    @TempDir Path directory;
    private final ContentAssetKey asset = new ContentAssetKey("site", 800000026087195706L, "media-v1", "public", 1);
    private static final Receipt RESULT = new Receipt("result-1", "a".repeat(64));
    private static CallControl control() { return new CallControl(Instant.now().plusSeconds(30), new CancellationToken()); }
    private Unit unit(String segment) { return new Unit(asset, Stage.TRANSCRIBE, segment, "asr-v1", 0, 30000); }
    private Grant grant() { return new Grant(UUID.randomUUID(), asset, Instant.now().plusSeconds(600)); }
    private Job enqueue(String segment, int attempts) { return store.enqueue(unit(segment), grant(), Instant.now().plusSeconds(300), attempts); }
    @BeforeAll static void open() {
        String url = System.getenv("AGENT_CONTENT_JDBC_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/video_content_jobs_it(?:\\?.*)?"), "Refuse any non-dedicated database");
        var config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername("root");
        config.setPassword(System.getenv().getOrDefault("AGENT_CONTENT_JDBC_PASSWORD", ""));
        config.setMaximumPoolSize(10); config.setMinimumIdle(0); config.setConnectionTimeout(5000);
        source = new HikariDataSource(config);
        assertEquals("utf8mb4_bin", new JdbcTemplate(source).queryForObject(
                "SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=DATABASE()", String.class),
                "Historical migrations require the dedicated database's default collation to be utf8mb4_bin");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
    }
    @AfterAll static void close() { if (source != null) source.close(); }
    @BeforeEach void reset() {
        db = new JdbcTemplate(source);
        db.update("DELETE FROM agent_content_subscription"); db.update("DELETE FROM agent_content_work");
        store = new MysqlContentWorkStore(source, new ObjectMapper());
    }
    @Test void typedTranscriptEvidenceSurvivesDurableRunRepositoryReload() {
        var json=new ObjectMapper().findAndRegisterModules();
        var viewer=new com.tianmu.agent.core.AgentTypes.AccessScope("transcript-it",UUID.randomUUID().toString(),Set.of(),"",Set.of(asset.videoId()));
        var context=new com.tianmu.agent.core.TranscriptContent.Context(viewer.tenantId(),asset.videoId(),"media-v1","public",1,120000,"asr-v1","CURRENT",0,60000);
        var facts=new com.tianmu.agent.core.TranscriptContent.Facts(context,"NO_TEXT",new com.tianmu.agent.core.TranscriptContent.Range(0,60000),List.of(),List.of(),List.of(),List.of("NO_TRANSCRIPT"),Instant.now());
        var objects=new com.tianmu.agent.adapters.storage.FileObjectStore(directory.resolve("run-evidence"));
        try(var cache=new com.tianmu.agent.adapters.storage.RedisMemoryCache(json,false,"127.0.0.1",0,"")) {
            UUID runId,sessionId;
            try(var repository=new com.tianmu.agent.adapters.storage.MysqlRunRepository(source,json,cache,objects,Duration.ofSeconds(30),16)) {
                var run=repository.create("typed audio evidence",viewer,null,UUID.randomUUID().toString(),null).run();runId=run.id;sessionId=run.sessionId;
                assertTrue(run.start());
                run.execution().evidence().put(new com.tianmu.agent.core.AgentTypes.Evidence("ev_transcript_it",run.id,viewer,null,null,null,null,null,null,null,facts));
                assertEquals(facts,run.execution().evidence().get("ev_transcript_it").transcript());
            }
            try(var restored=new com.tianmu.agent.adapters.storage.MysqlRunRepository(source,json,cache,objects,Duration.ofSeconds(30),16)) {
                try {
                    var result=restored.load(runId,viewer).execution().evidence().get("ev_transcript_it");
                    assertEquals(facts,result.transcript());assertNull(result.videoContent());assertNull(result.data());
                }finally{restored.deleteSession(sessionId,viewer);}
            }
        }
    }
    @Test void sharedAdmissionAndPerUserCancellationSurviveRepositoryRestart() {
        Job first = enqueue("one", 3), duplicate = enqueue("one", 3);
        assertEquals(first.id(), duplicate.id());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.subscribe(first.id(), "site", "alice", a, Instant.now().plusSeconds(60));
        store.subscribe(first.id(), "site", "bob", b, Instant.now().plusSeconds(60));
        assertFalse(store.cancelSubscription("site", "bob", a));
        assertTrue(store.cancelSubscription("site", "alice", a));
        assertEquals(1, store.activeSubscribers(first.id()));
        var restored = new MysqlContentWorkStore(source, new ObjectMapper());
        Lease lease = restored.claim("worker-b", Duration.ofSeconds(30)).orElseThrow();
        assertEquals(first.id(), lease.jobId());
        assertTrue(restored.complete(lease, RESULT));
        assertEquals(1, restored.activeSubscribers(first.id()));
        assertEquals(Status.SUCCEEDED, store.get(first.id()).status());
    }
    @Test void concurrentWorkersCannotClaimTheSameGeneration() throws Exception {
        enqueue("race", 3);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
            List<Future<Optional<Lease>>> results = new ArrayList<>();
            for (int i=0; i<8; i++) {
                String worker = "worker-" + i;
                results.add(pool.submit(() -> { ready.countDown(); assertTrue(start.await(5,TimeUnit.SECONDS));
                    return new MysqlContentWorkStore(source, new ObjectMapper()).claim(worker, Duration.ofSeconds(30)); }));
            }
            assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(15,TimeUnit.SECONDS).isPresent()) winners++;
            assertEquals(1, winners);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)); }
    }
    @Test void leaseTakeoverFencesOldWorkerHeartbeatAndResultSubmission() {
        Job job = enqueue("restart", 3);
        Lease old = store.claim("crashed", Duration.ofSeconds(30)).orElseThrow();
        db.update("UPDATE agent_content_work SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=?", job.id().toString());
        var restored = new MysqlContentWorkStore(source, new ObjectMapper());
        Lease fresh = restored.claim("replacement", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(fresh.fence() > old.fence());
        assertFalse(store.heartbeat(old, Duration.ofSeconds(30)));
        assertFalse(store.complete(old, RESULT));
        assertTrue(restored.complete(fresh, RESULT));
        assertTrue(restored.complete(fresh, RESULT), "Unknown commit acknowledgement is reconciled idempotently");
        assertFalse(restored.complete(fresh, new Receipt("result-2", "b".repeat(64))));
        assertEquals(2, restored.get(job.id()).attempts());
    }
    @Test void retryBudgetIsDurableAndExpiredLastAttemptIsNotRedispatched() {
        Job job = enqueue("bounded", 2);
        Lease first = store.claim("one", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(store.fail(first, "UPSTREAM_TIMEOUT", true, Duration.ofSeconds(60)));
        assertTrue(store.claim("too-soon", Duration.ofSeconds(30)).isEmpty());
        db.update("UPDATE agent_content_work SET ready_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=?", job.id().toString());
        Lease last = store.claim("two", Duration.ofSeconds(30)).orElseThrow();
        db.update("UPDATE agent_content_work SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=?", job.id().toString());
        assertTrue(store.claim("three", Duration.ofSeconds(30)).isEmpty());
        assertEquals(Status.FAILED, store.get(job.id()).status());
        assertFalse(store.complete(last, RESULT));
    }
    @Test void deadlineExpiresQueuedAndRunningJobsWithoutAcceptingLateResults() {
        Job job = enqueue("deadline", 3);
        Lease lease = store.claim("worker", Duration.ofSeconds(30)).orElseThrow();
        db.update("UPDATE agent_content_work SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=?", job.id().toString());
        assertTrue(store.claim("next", Duration.ofSeconds(30)).isEmpty());
        assertEquals(Status.EXPIRED, store.get(job.id()).status());
        assertFalse(store.complete(lease, RESULT));
    }
    @Test void revocationAtCommitPreventsResultPublicationAndReclaim() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        List<Checkpoint> checks = new ArrayList<>();
        ContentProcessingAuthority authority = (grant, point, control) -> {
            checks.add(point);
            if (!allowed.get()) throw new AgentException("CONTENT_GRANT_REVOKED", "revoked");
        };
        var service = new ContentWorkService(store, authority, Duration.ofSeconds(1));
        Job job = service.admit(unit("authority"), grant(), Instant.now().plusSeconds(300), 3, control());
        Lease lease = service.claim("worker", Duration.ofSeconds(30), control()).orElseThrow();
        service.requireDispatch(lease, control());
        allowed.set(false);
        assertEquals("CONTENT_GRANT_REVOKED", assertThrows(AgentException.class, () -> service.complete(lease, RESULT, control())).code());
        assertEquals(List.of(Checkpoint.ADMISSION,Checkpoint.CLAIM,Checkpoint.DISPATCH,Checkpoint.COMMIT), checks);
        assertEquals(Status.REVOKED, store.get(job.id()).status());
        assertNull(store.get(job.id()).result());
        assertTrue(store.claim("other", Duration.ofSeconds(30)).isEmpty());
    }
    @Test void authorityOutageRetriesWithoutBeingMislabelledAsRevocation() {
        Job job = enqueue("authority-outage", 3);
        var service = new ContentWorkService(store, (grant, point, control) -> {
            throw new AgentException("BUSINESS_UNAVAILABLE", "outage");
        }, Duration.ofSeconds(1));
        assertThrows(AgentException.class, () -> service.claim("worker", Duration.ofSeconds(30), control()));
        assertEquals(Status.RETRY_WAIT, store.get(job.id()).status());
    }
    @Test void explicitRetryRenewsAuthorityButCannotResetAttemptsOrReviveRevokedWork() {
        Job job = enqueue("manual-retry", 1);
        Lease first = store.claim("first", Duration.ofSeconds(30)).orElseThrow();
        assertTrue(store.fail(first, "MODEL_FAILED", false, Duration.ofSeconds(1)));
        var service = new ContentWorkService(store, (grant, point, control) -> {}, Duration.ofSeconds(1));
        assertTrue(service.retry(job.id(), grant(), Instant.now().plusSeconds(60), 1, control()));
        Lease second = service.claim("second", Duration.ofSeconds(30), control()).orElseThrow();
        assertEquals(2, store.get(job.id()).attempts());
        assertFalse(store.complete(first, RESULT));
        assertTrue(store.revoke(second));
        assertFalse(service.retry(job.id(), grant(), Instant.now().plusSeconds(60), 1, control()));
        assertEquals(Status.REVOKED, store.get(job.id()).status());
    }
    @Test void repeatedManualRetryCannotBypassLifetimeAttemptCap() {
        Job job = enqueue("lifetime-budget", 1);
        var service = new ContentWorkService(store, (grant, point, control) -> {}, Duration.ofSeconds(1));
        for (int attempt = 1; attempt <= 10; attempt++) {
            Lease lease = service.claim("worker", Duration.ofSeconds(30), control()).orElseThrow();
            assertEquals(attempt, store.get(job.id()).attempts());
            assertTrue(store.fail(lease, "MODEL_FAILED", false, Duration.ofSeconds(1)));
            if (attempt < 10) assertTrue(service.retry(job.id(), grant(), Instant.now().plusSeconds(60), 1, control()));
        }
        assertEquals("CONTENT_RETRY_BUDGET_EXHAUSTED", assertThrows(AgentException.class,
                () -> service.retry(job.id(), grant(), Instant.now().plusSeconds(60), 1, control())).code());
        assertEquals(10, store.get(job.id()).attempts());
        assertTrue(store.claim("extra", Duration.ofSeconds(30)).isEmpty());
    }
    @Test void identityIncludesAuthorizationDomainAndRevocationEpochButNotMutableRange() {
        enqueue("identity", 3);
        var altered = new Unit(asset, Stage.TRANSCRIBE, "identity", "asr-v1", 0, 31000);
        assertEquals("CONTENT_WORK_CONFLICT", assertThrows(AgentException.class,
                () -> store.enqueue(altered, grant(), Instant.now().plusSeconds(60), 3)).code());
        var changed = new ContentAssetKey("site", asset.videoId(), asset.sourceVersion(), "public", 2);
        Job other = store.enqueue(new Unit(changed, Stage.TRANSCRIBE, "identity", "asr-v1",0,30000),
                new Grant(UUID.randomUUID(),changed,Instant.now().plusSeconds(300)),Instant.now().plusSeconds(60),3);
        assertEquals(changed, other.unit().asset());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM agent_content_work", Integer.class));
    }
    private ContentProcessingMediaPort media() {
        return (grant,from,to,control)->new VideoContent.Media(
                new VideoContent.Source(asset.videoId(),asset.sourceVersion(),60000),from,to,List.of(),Base64.getEncoder().encodeToString(TestWave.pcm(to-from)));
    }
    private ContentTranscriptionWorker worker(ContentWorkService service,ContentProcessingMediaPort media,TranscriptionPort asr,ContentTranscriptStore results) {
        return new ContentTranscriptionWorker(store,service,media,asr,results,"transcriber","asr-v1",Duration.ofSeconds(30),Duration.ofSeconds(5));
    }
    @Test void workerProcessesOnlyItsRegisteredStageAndVersionAndPersistsPrivateResult()throws Exception {
        var grant=grant();
        store.enqueue(new Unit(asset,Stage.VISION,"frame","vision-v1",0,30000),grant,Instant.now().plusSeconds(60),3);
        store.enqueue(new Unit(asset,Stage.TRANSCRIBE,"other-model","asr-v2",0,30000),grant,Instant.now().plusSeconds(60),3);
        Job job=enqueue("worker",3);
        var service=new ContentWorkService(store,(g,point,c)->{},Duration.ofSeconds(1));
        var files=new FileContentTranscriptStore(directory,new ObjectMapper());
        AtomicInteger calls=new AtomicInteger();
        try(var worker=worker(service,media(),(audio,c)->{calls.incrementAndGet();return "定义域的讲解";},files)) {
            assertEquals(ContentTranscriptionWorker.Outcome.SUCCEEDED,worker.runOnce(control()));
            assertEquals(ContentTranscriptionWorker.Outcome.NO_WORK,worker.runOnce(control()));
        }
        Job done=store.get(job.id());assertEquals(Status.SUCCEEDED,done.status());files.verify(done,done.result());
        assertEquals(1,calls.get());
        String saved=Files.readString(directory.resolve(job.id()+"-1.json"));
        assertFalse(saved.contains(job.grant().id().toString()),"Platform credential references must not enter content artifacts");
        assertTrue(saved.contains("定义域的讲解"));
    }
    @Test void recoveredPreparedResultDoesNotRepeatExtractionOrAsr()throws Exception {
        Job job=enqueue("checkpoint",3);Lease first=store.claim("crashed",Duration.ofSeconds(30)).orElseThrow();
        var files=new FileContentTranscriptStore(directory,new ObjectMapper());
        var transcript=new TimedTranscript(0,30000,"已完成讲解",TimedTranscript.State.AVAILABLE,TimedTranscript.Precision.CLIP,
                List.of(new TimedTranscript.Segment(0,30000,"已完成讲解")));
        Receipt receipt=files.save(store.requireLease(first),first,transcript);assertTrue(store.stageResult(first,receipt));
        db.update("UPDATE agent_content_work SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=?",job.id().toString());
        AtomicInteger calls=new AtomicInteger();var service=new ContentWorkService(store,(g,p,c)->{},Duration.ofSeconds(1));
        try(var worker=worker(service,(g,f,t,c)->{calls.incrementAndGet();throw new AssertionError("No repeat media extraction");},
                (audio,c)->{calls.incrementAndGet();throw new AssertionError("No repeat ASR");},files)) {
            assertEquals(ContentTranscriptionWorker.Outcome.SUCCEEDED,worker.runOnce(control()));
        }
        assertEquals(0,calls.get());assertEquals(receipt,store.get(job.id()).result());
        assertFalse(store.complete(first,receipt));
    }
    @Test void revokedGrantAfterAsrNeverPublishesPreparedResult()throws Exception {
        Job job=enqueue("revoke-model",3);AtomicBoolean allowed=new AtomicBoolean(true);
        var service=new ContentWorkService(store,(g,p,c)->{if(!allowed.get())throw new AgentException("CONTENT_GRANT_REVOKED","revoked");},Duration.ofSeconds(1));
        try(var worker=worker(service,media(),(audio,c)->{allowed.set(false);return "模型结果";},new FileContentTranscriptStore(directory,new ObjectMapper()))) {
            assertEquals(ContentTranscriptionWorker.Outcome.FAILED,worker.runOnce(control()));
        }
        assertEquals(Status.REVOKED,store.get(job.id()).status());
    }
    @Test void closeDoesNotFinishAnUninterruptibleModelInvocationEarly()throws Exception {
        enqueue("native-call",3);var service=new ContentWorkService(store,(g,p,c)->{},Duration.ofSeconds(1));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var worker=worker(service,media(),(audio,c)->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}return "late";},
                new FileContentTranscriptStore(directory,new ObjectMapper()));
        var executor=Executors.newSingleThreadExecutor();
        try {
            Future<ContentTranscriptionWorker.Outcome> pending=executor.submit(()->worker.runOnce(control()));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals(ContentTranscriptionWorker.Outcome.BUSY,worker.runOnce(control()));
            worker.close();assertThrows(TimeoutException.class,()->pending.get(150,TimeUnit.MILLISECONDS));
            release.countDown();assertEquals(ContentTranscriptionWorker.Outcome.FAILED,pending.get(10,TimeUnit.SECONDS));
            assertEquals(ContentTranscriptionWorker.Outcome.CLOSED,worker.runOnce(control()));
        }finally{release.countDown();worker.close();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void longRunningTranscriptionRenewsItsLeaseWithoutChangingGeneration()throws Exception {
        Job job=enqueue("heartbeat",3);AtomicInteger dispatchChecks=new AtomicInteger();
        var service=new ContentWorkService(store,(g,p,c)->{if(p==Checkpoint.DISPATCH)dispatchChecks.incrementAndGet();},Duration.ofSeconds(1));
        TranscriptionPort asr=(audio,c)->{
            Instant originalExpiry=store.get(job.id()).leaseUntil();
            while(Instant.now().isBefore(originalExpiry.plusMillis(300))) {
                c.checkpoint();try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }
            return "长调用完成";
        };
        try(var worker=new ContentTranscriptionWorker(store,service,media(),asr,new FileContentTranscriptStore(directory,new ObjectMapper()),
                "heartbeat-worker","asr-v1",Duration.ofSeconds(6),Duration.ofSeconds(1))) {
            assertEquals(ContentTranscriptionWorker.Outcome.SUCCEEDED,worker.runOnce(control()));
        }
        assertTrue(dispatchChecks.get()>=5);assertEquals(1,store.get(job.id()).attempts());
        assertEquals(1,store.get(job.id()).fence());
    }
    private Job publish(String segment,long from,long to,List<TimedTranscript.Segment> segments)throws Exception {
        var unit=new Unit(asset,Stage.TRANSCRIBE,segment,"asr-v1",from,to);
        Job job=store.enqueue(unit,grant(),Instant.now().plusSeconds(300),3);
        Lease lease=store.claim("publisher",Duration.ofSeconds(30),Stage.TRANSCRIBE,"asr-v1").orElseThrow();
        assertEquals(job.id(),lease.jobId());
        String text=segments.stream().map(TimedTranscript.Segment::text).collect(java.util.stream.Collectors.joining());
        var transcript=new TimedTranscript(from,to,text,segments.isEmpty()?TimedTranscript.State.NO_TRANSCRIPT:TimedTranscript.State.AVAILABLE,
                TimedTranscript.Precision.SEGMENT,segments);
        var files=new FileContentTranscriptStore(directory,new ObjectMapper());
        Receipt receipt=files.save(store.requireLease(lease),lease,transcript);assertTrue(store.complete(lease,receipt));return store.get(job.id());
    }
    private com.tianmu.agent.core.AgentTypes.AccessScope viewer(){return new com.tianmu.agent.core.AgentTypes.AccessScope("site","reader",Set.of(),"read-auth",Set.of(asset.videoId()));}
    private TranscriptQueryProperties readPolicy(int candidates,int concurrency){return new TranscriptQueryProperties("asr-v1",directory.toString(),60000,candidates,12,8000,4194304,concurrency,15);}
    private TranscriptRetrievalService reader()throws Exception {
        return new TranscriptRetrievalService(store,new FileContentTranscriptStore(directory,new ObjectMapper()),
                (id,v,c)->new ContentReadAuthority.Identity(asset,60000),readPolicy(128,4));
    }
    private TranscriptRetrievalService.Request current(long anchor){return new TranscriptRetrievalService.Request(asset.sourceVersion(),TranscriptRetrievalService.Mode.CURRENT,anchor,null,null,"");}
    @Test void queryReturnsOnlyPublishedVerifiedTextAndCitationCanBeRevalidated()throws Exception {
        publish("read",0,30000,List.of(new TimedTranscript.Segment(0,10000,"每个输入只有一个输出"),new TimedTranscript.Segment(10000,30000,"不同输入可以对应同一个输出")));
        var reader=reader();var bundle=reader.query(asset.videoId(),viewer(),current(30000),control());
        assertEquals(TranscriptRetrievalService.Status.READY,bundle.status());assertEquals(2,bundle.excerpts().size());assertTrue(bundle.missingRanges().isEmpty());
        var excerpt=bundle.excerpts().get(1);
        assertEquals(excerpt,reader.citation(asset.videoId(),viewer(),current(30000),excerpt.citationId(),control()));
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(bundle).contains("grant"));
    }
    @Test void sentenceTimestampCannotHideFutureAudioDependency()throws Exception {
        publish("previous",0,30000,List.of(new TimedTranscript.Segment(0,30000,"已讲的定义")));
        publish("future-clip",30000,60000,List.of(new TimedTranscript.Segment(30000,35000,"后文答案影响的转写")));
        var reader=reader();var bounded=reader.query(asset.videoId(),viewer(),current(35000),control());
        assertEquals(TranscriptRetrievalService.Status.PARTIAL,bounded.status());assertEquals(1,bounded.excerpts().size());
        assertTrue(bounded.limitations().contains("DEPENDENCY_OUTSIDE_WINDOW"));assertEquals("已讲的定义",bounded.excerpts().get(0).text());
        assertEquals(List.of(new TranscriptRetrievalService.Range(30000,35000)),bounded.missingRanges());
        var explicit=new TranscriptRetrievalService.Request(asset.sourceVersion(),TranscriptRetrievalService.Mode.INTERVAL,null,30000L,60000L,"");
        assertEquals("后文答案影响的转写",reader.query(asset.videoId(),viewer(),explicit,control()).excerpts().get(0).text());
    }
    @Test void completedAudioWithNoTextIsNotClaimedAsSilenceOrReady()throws Exception {
        publish("empty",0,30000,List.of());var bundle=reader().query(asset.videoId(),viewer(),current(30000),control());
        assertEquals(TranscriptRetrievalService.Status.NO_TEXT,bundle.status());assertTrue(bundle.limitations().contains("NO_TRANSCRIPT"));
        assertTrue(bundle.excerpts().isEmpty());
    }
    @Test void corruptedResultCannotProduceExcerptOrCitation()throws Exception {
        var job=publish("corrupt",0,30000,List.of(new TimedTranscript.Segment(0,30000,"原文")));
        var reader=reader();String id=reader.query(asset.videoId(),viewer(),current(30000),control()).excerpts().get(0).citationId();
        Files.writeString(directory.resolve(job.id()+"-1.json"),"tampered");
        var result=reader.query(asset.videoId(),viewer(),current(30000),control());
        assertTrue(result.excerpts().isEmpty());assertTrue(result.limitations().contains("RESULT_UNAVAILABLE"));
        assertEquals("CONTENT_RESULT_UNAVAILABLE",assertThrows(AgentException.class,()->reader.citation(asset.videoId(),viewer(),current(30000),id,control())).code());
    }
    @Test void revocationDuringFileReadRejectsTheEntireResponse()throws Exception {
        publish("read-revoke",0,30000,List.of(new TimedTranscript.Segment(0,30000,"私有结果")));
        var files=new FileContentTranscriptStore(directory,new ObjectMapper());AtomicBoolean allowed=new AtomicBoolean(true);
        ContentTranscriptStore wrapped=new ContentTranscriptStore(){
            public Receipt save(Job j,Lease l,TimedTranscript t){return files.save(j,l,t);}
            public void verify(Job j,Receipt r){files.verify(j,r);}
            public Loaded read(Job j,Receipt r,int limit){var value=files.read(j,r,limit);allowed.set(false);return value;}
        };
        var reader=new TranscriptRetrievalService(store,wrapped,(id,v,c)->{if(!allowed.get())throw new AgentException("FORBIDDEN","revoked");return new ContentReadAuthority.Identity(asset,60000);},readPolicy(128,4));
        assertEquals("FORBIDDEN",assertThrows(AgentException.class,()->reader.query(asset.videoId(),viewer(),current(30000),control())).code());
    }
    @Test void republishedVideoDoesNotReviveOldCitationsAndStaleVersionIsRejected()throws Exception {
        publish("epoch",0,30000,List.of(new TimedTranscript.Segment(0,30000,"旧代次")));
        var live=new java.util.concurrent.atomic.AtomicReference<>(new ContentReadAuthority.Identity(asset,60000));
        var reader=new TranscriptRetrievalService(store,new FileContentTranscriptStore(directory,new ObjectMapper()),(id,v,c)->live.get(),readPolicy(128,4));
        String id=reader.query(asset.videoId(),viewer(),current(30000),control()).excerpts().get(0).citationId();
        live.set(new ContentReadAuthority.Identity(new ContentAssetKey("site",asset.videoId(),asset.sourceVersion(),"public",2),60000));
        assertTrue(reader.query(asset.videoId(),viewer(),current(30000),control()).excerpts().isEmpty());
        assertEquals("CONTENT_CITATION_NOT_FOUND",assertThrows(AgentException.class,()->reader.citation(asset.videoId(),viewer(),current(30000),id,control())).code());
        live.set(new ContentReadAuthority.Identity(new ContentAssetKey("site",asset.videoId(),"new-version","public",2),60000));
        assertEquals("VIDEO_VERSION_CHANGED",assertThrows(AgentException.class,()->reader.query(asset.videoId(),viewer(),current(30000),control())).code());
    }
    @Test void candidateCapReportsMissingCoverageInsteadOfClaimingComplete()throws Exception {
        publish("first",0,15000,List.of(new TimedTranscript.Segment(0,15000,"第一段")));
        publish("second",15000,30000,List.of(new TimedTranscript.Segment(15000,30000,"第二段")));
        var reader=new TranscriptRetrievalService(store,new FileContentTranscriptStore(directory,new ObjectMapper()),(id,v,c)->new ContentReadAuthority.Identity(asset,60000),readPolicy(1,4));
        var result=reader.query(asset.videoId(),viewer(),current(30000),control());
        assertEquals(TranscriptRetrievalService.Status.PARTIAL,result.status());assertTrue(result.limitations().contains("CANDIDATE_LIMIT"));
        assertFalse(result.missingRanges().isEmpty());
    }
    @Test void readerQuotaIsHeldUntilActualReadAndAuthorityChecksFinish()throws Exception {
        publish("quota",0,30000,List.of(new TimedTranscript.Segment(0,30000,"内容")));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger checks=new AtomicInteger();
        var reader=new TranscriptRetrievalService(store,new FileContentTranscriptStore(directory,new ObjectMapper()),(id,v,c)->{
            if(checks.getAndIncrement()==0){entered.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}}
            return new ContentReadAuthority.Identity(asset,60000);
        },readPolicy(128,1));
        var pool=Executors.newSingleThreadExecutor();
        try {
            var first=pool.submit(()->reader.query(asset.videoId(),viewer(),current(30000),control()));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("CONTENT_QUERY_BUSY",assertThrows(AgentException.class,()->reader.query(asset.videoId(),viewer(),current(30000),control())).code());
            release.countDown();assertEquals(TranscriptRetrievalService.Status.READY,first.get(10,TimeUnit.SECONDS).status());
            assertNotNull(reader.identity(asset.videoId(),viewer(),control()));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
