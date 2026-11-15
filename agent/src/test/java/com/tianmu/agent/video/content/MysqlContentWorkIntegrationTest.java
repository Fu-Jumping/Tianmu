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
                new VideoContent.Source(asset.videoId(),asset.sourceVersion(),60000),from,to,List.of(),"AQID");
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
}
