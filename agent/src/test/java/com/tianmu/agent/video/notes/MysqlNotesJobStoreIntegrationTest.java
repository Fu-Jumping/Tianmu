package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.video.content.ContentAssetKey;
import com.tianmu.agent.video.content.ContentWork;
import com.tianmu.agent.video.content.MysqlContentWorkStore;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import com.zaxxer.hikari.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static com.tianmu.agent.video.notes.NotesJob.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in isolated MySQL only; no business or production database is accepted. */
@EnabledIfEnvironmentVariable(named="AGENT_NOTES_IT", matches="true")
class MysqlNotesJobStoreIntegrationTest {
    private static HikariDataSource source;
    private MysqlNotesJobStore store;
    private String user;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path files;
    private final Policy policy=new Policy("zh","notes-v1","notes-material-v1","notes-generate-v1",60000,5000,100);
    @BeforeAll static void open() {
        String url=System.getenv("AGENT_NOTES_JDBC_URL"); assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/video_notes_jobs_it(?:\\?.*)?"),"Refuse non-dedicated database");
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("root");
        config.setPassword(System.getenv().getOrDefault("AGENT_NOTES_JDBC_PASSWORD",""));config.setMaximumPoolSize(8);
        source=new HikariDataSource(config);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
    }
    @AfterAll static void close(){if(source!=null)source.close();}
    @BeforeEach void setup(){
        var db=new JdbcTemplate(source);
        db.update("DELETE FROM agent_video_notes_document");
        db.update("DELETE FROM agent_video_notes_call");db.update("DELETE FROM agent_video_notes_dependency");
        db.update("DELETE FROM agent_video_notes_binding");db.update("DELETE FROM agent_video_notes_job");
        db.update("DELETE FROM agent_content_work");
        store=new MysqlNotesJobStore(source,new ObjectMapper());user=UUID.randomUUID().toString();
    }
    private Create request(String key){return new Create("site",user,new ContentAssetKey("site",123,"v1","public",1),125000,policy,key,Instant.now().plusSeconds(600));}
    private Job running(){var j=store.create(request("k"));assertTrue(store.installPlan(j.id(),"site",user,j.version(),sourcePlan(j.id(),125000,policy,"a".repeat(64))));return store.get(j.id(),"site",user);}
    private record Dispatch(Job job, PlanUnit unit, ContentWork.Lease lease, Call call) {}
    private Dispatch dispatchable() {
        var job=running();var unit=store.plan(job.id(),"site",user).get(0);
        var works=new MysqlContentWorkStore(source,new ObjectMapper());
        var work=works.enqueue(new ContentWork.Unit(job.request().asset(),unit.stage(),unit.segmentId(job.id()),policy.analysisVersion(),unit.dependencyStartMs(),unit.dependencyEndMs()),
                new ContentWork.Grant(UUID.randomUUID(),job.request().asset(),Instant.now().plusSeconds(600)),Instant.now().plusSeconds(300),3);
        assertTrue(store.bindWork(job.id(),"site",user,job.generation(),unit.unitKey(),work.id()));
        // Claim the exact work in the dedicated database; other successful tests may leave unrelated queued work.
        var db=new JdbcTemplate(source);db.update("UPDATE agent_content_work SET status='RUNNING',fence=1,lease_owner='notes-it',lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)) WHERE job_id=?",work.id().toString());
        var lease=new ContentWork.Lease(work.id(),1,"notes-it");
        return new Dispatch(job,unit,lease,store.prepareCall(job.id(),"site",user,1,unit.segmentId(job.id()),70));
    }
    @Test void duplicateCreationRetainsIdentityAndDeadlineAcrossRestart() {
        var first=store.create(request("k"));var again=new MysqlNotesJobStore(source,new ObjectMapper()).create(request("k"));
        assertEquals(first.id(),again.id());assertEquals(first.request().deadline(),again.request().deadline());
        var other=request("k");
        assertThrows(RuntimeException.class,()->store.create(new Create("site",user,new ContentAssetKey("site",123,"v2","public",1),125000,policy,"k",other.deadline())));
        assertThrows(RuntimeException.class,()->store.get(first.id(),"site","other-user"));
    }
    @Test void concurrentCreationAndBudgetReservationHaveOneWinner() throws Exception {
        var request=request("same"); var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=pool.invokeAll(List.<Callable<Job>>of(()->store.create(request),()->store.create(request),()->store.create(request)));
            UUID id=futures.get(0).get().id();for(var f:futures)assertEquals(id,f.get().id());
            var job=store.get(id,"site",user);assertTrue(store.installPlan(id,"site",user,job.version(),sourcePlan(id,125000,policy,"a".repeat(64))));
            Callable<Boolean> a=()->{try{store.prepareCall(id,"site",user,1,UUID.randomUUID().toString(),70);return true;}catch(com.tianmu.agent.core.AgentException denied){return false;}};
            var results=pool.invokeAll(List.of(a,a));int accepted=0;for(var f:results)if(f.get())accepted++;
            assertEquals(1,accepted);assertEquals(70,store.get(id,"site",user).reservedMicros());
        }finally{pool.shutdownNow();}
    }
    @Test void unknownDispatchCannotAutomaticallyRepeatAndRetainsBudget() {
        var d=dispatchable();var job=d.job();var call=d.call();
        assertTrue(store.beginWorkDispatch(call.id(),d.lease()));assertTrue(store.markUnknown(call.id()));
        assertFalse(store.beginWorkDispatch(call.id(),d.lease()));assertFalse(store.stageWorkResult(call.id(),d.lease(),"result","a".repeat(64)));
        assertFalse(store.commitCall(call.id(),0));assertEquals(70,store.get(job.id(),"site",user).reservedMicros());
        var missing=store.dependencies(job.id(),"site",user).stream().filter(dep->dep.workId()==null).findFirst().orElseThrow();
        assertTrue(store.enqueueBound(job.id(),"site",user,job.version(),1,missing.unit().unitKey(),
                new ContentWork.Grant(UUID.randomUUID(),job.request().asset(),Instant.now().plusSeconds(600)),Instant.now().plusSeconds(300),3).isEmpty());
    }
    @Test void explicitRetryChargesUnknownAndRetainsTheBoundWork() {
        var d=dispatchable();var db=new JdbcTemplate(source);var root=d.job();
        assertTrue(store.beginWorkDispatch(d.call().id(),d.lease()));assertTrue(store.markUnknown(d.call().id()));
        db.update("UPDATE agent_content_work SET status='FAILED',attempts=1 WHERE job_id=?",d.lease().jobId().toString());
        assertTrue(store.transition(root.id(),"site",user,root.version(),Status.PARTIAL,"NOTES_CALL_UNKNOWN"));
        var partial=store.get(root.id(),"site",user);
        var grant=new ContentWork.Grant(UUID.randomUUID(),root.request().asset(),Instant.now().plusSeconds(500));
        assertFalse(store.retryUnknown(root.id(),"site",user,partial.version(),d.lease().jobId(),grant,Instant.now().plusSeconds(300)));
        db.update("UPDATE agent_video_notes_job SET budget_micros=200 WHERE job_id=?",root.id().toString());
        assertTrue(store.retryUnknown(root.id(),"site",user,partial.version(),d.lease().jobId(),grant,Instant.now().plusSeconds(300)));
        assertFalse(store.retryUnknown(root.id(),"site",user,partial.version(),d.lease().jobId(),grant,Instant.now().plusSeconds(300)));
        var resumed=store.get(root.id(),"site",user);assertEquals(Status.RUNNING,resumed.status());
        assertEquals(70,resumed.usedMicros());assertEquals(0,resumed.reservedMicros());assertFalse(store.hasUnknownCalls(root.id(),"site",user));
        assertEquals(ContentWork.Status.QUEUED,new MysqlContentWorkStore(source,new ObjectMapper()).get(d.lease().jobId()).status());
        var next=store.prepareCall(root.id(),"site",user,1,d.unit().segmentId(root.id()),70);
        assertNotEquals(d.call().id(),next.id());assertEquals(70,store.get(root.id(),"site",user).reservedMicros());
        assertEquals("UNKNOWN_CHARGED",db.queryForObject("SELECT state FROM agent_video_notes_call WHERE call_id=?",String.class,d.call().id().toString()));
    }
    @Test void stagedReceiptCommitIsIdempotentAndDoesNotDoubleCharge() {
        var d=dispatchable();var job=d.job();var call=d.call();
        assertTrue(store.beginWorkDispatch(call.id(),d.lease()));assertTrue(store.stageWorkResult(call.id(),d.lease(),"result","a".repeat(64)));
        assertFalse(store.commitCall(call.id(),71));assertTrue(store.commitCall(call.id(),50));assertTrue(store.commitCall(call.id(),50));
        var restored=store.get(job.id(),"site",user);assertEquals(50,restored.usedMicros());assertEquals(0,restored.reservedMicros());
    }
    @Test void staleFenceAndCrossRootCannotDispatch() {
        var d=dispatchable();
        assertEquals(d.job().id(),store.lookupBoundWork(d.lease().jobId()).orElseThrow().job().id());
        assertFalse(store.beginWorkDispatch(d.call().id(),new ContentWork.Lease(d.lease().jobId(),2,"notes-it")));
        assertFalse(store.beginWorkDispatch(d.call().id(),new ContentWork.Lease(d.lease().jobId(),1,"other")));
        user=UUID.randomUUID().toString();var other=dispatchable();assertFalse(store.beginWorkDispatch(d.call().id(),other.lease()));
    }
    @Test void crashedDispatchRecoversOnlyAfterLeaseExpiresAndCannotReplay() {
        var d=dispatchable();assertTrue(store.beginWorkDispatch(d.call().id(),d.lease()));
        assertEquals(0,store.recoverExpiredDispatches(128));
        new JdbcTemplate(source).update("UPDATE agent_content_work SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=?",d.lease().jobId().toString());
        assertEquals(1,store.recoverExpiredDispatches(128));assertEquals(0,store.recoverExpiredDispatches(128));
        assertFalse(store.beginWorkDispatch(d.call().id(),d.lease()));assertFalse(store.stageWorkResult(d.call().id(),d.lease(),"late","b".repeat(64)));
        assertEquals(70,store.get(d.job().id(),"site",user).reservedMicros());
    }
    @Test void simultaneousDispatchHasExactlyOneSendPermission() throws Exception {
        var d=dispatchable();var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> attempt=()->store.beginWorkDispatch(d.call().id(),d.lease());
            var results=pool.invokeAll(List.of(attempt,attempt));int granted=0;for(var f:results)if(f.get())granted++;
            assertEquals(1,granted);
        }finally{pool.shutdownNow();}
    }
    @Test void wrongOperationAndExpiredGrantCannotDispatch() throws Exception {
        var d=dispatchable();var db=new JdbcTemplate(source);
        db.update("UPDATE agent_video_notes_call SET operation_key='unbound-operation' WHERE call_id=?",d.call().id().toString());
        assertFalse(store.beginWorkDispatch(d.call().id(),d.lease()));
        db.update("UPDATE agent_video_notes_call SET operation_key=? WHERE call_id=?",d.unit().segmentId(d.job().id()),d.call().id().toString());
        String grant=new ObjectMapper().findAndRegisterModules().writeValueAsString(new ContentWork.Grant(UUID.randomUUID(),d.job().request().asset(),Instant.now().minusSeconds(1)));
        db.update("UPDATE agent_content_work SET grant_json=? WHERE job_id=?",grant,d.lease().jobId().toString());
        assertFalse(store.beginWorkDispatch(d.call().id(),d.lease()));
    }
    @Test void recoveryDiscoveryAndBindingSurviveStoreRestart() {
        var d=dispatchable();var restored=new MysqlNotesJobStore(source,new ObjectMapper());
        assertTrue(restored.scanActive(128).stream().anyMatch(j->j.id().equals(d.job().id())));
        var dependencies=restored.dependencies(d.job().id(),"site",user);
        assertEquals(6,dependencies.size());
        assertEquals(1,dependencies.stream().filter(dep->dep.workId()!=null).count());
        var bound=dependencies.stream().filter(dep->dep.workId()!=null).findFirst().orElseThrow();
        assertEquals(d.lease().jobId(),bound.workId());assertEquals(d.unit(),bound.unit());
        assertThrows(RuntimeException.class,()->restored.dependencies(d.job().id(),"site","other"));
        assertFalse(restored.hasUnknownCalls(d.job().id(),"site",user));
        assertTrue(restored.beginWorkDispatch(d.call().id(),d.lease()));assertTrue(restored.markUnknown(d.call().id()));
        assertTrue(restored.hasUnknownCalls(d.job().id(),"site",user));
        assertThrows(RuntimeException.class,()->restored.hasUnknownCalls(d.job().id(),"site","other"));
    }
    @Test void degradationIsVersionedScopedAndCannotPublishOrResume() {
        var job=running();
        assertThrows(IllegalArgumentException.class,()->store.transition(job.id(),"site",user,job.version(),Status.READY,"BAD"));
        assertThrows(RuntimeException.class,()->store.transition(job.id(),"site","other",job.version(),Status.PARTIAL,"MISSING"));
        assertFalse(store.transition(job.id(),"site",user,job.version()+1,Status.PARTIAL,"MISSING"));
        assertFalse(store.transition(job.id(),"site",user,job.version(),Status.EXPIRED,"TIMEOUT"));
        assertTrue(store.transition(job.id(),"site",user,job.version(),Status.PARTIAL,"MISSING"));
        var degraded=store.get(job.id(),"site",user);
        assertEquals(Status.PARTIAL,degraded.status());assertEquals(job.version()+1,degraded.version());
        assertEquals("MISSING",store.errorCode(job.id(),"site",user));
        assertFalse(store.transition(job.id(),"site",user,degraded.version(),Status.FAILED,"OTHER"));
        assertTrue(store.scanActive(128).stream().noneMatch(j->j.id().equals(job.id())));
    }
    @Test void scanExcludesPastDeadlineButExpiryCanBeRecorded() {
        var job=store.create(request("k"));
        new JdbcTemplate(source).update("UPDATE agent_video_notes_job SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=?",job.id().toString());
        assertTrue(store.scanActive(128).isEmpty());
        assertTrue(store.transition(job.id(),"site",user,job.version(),Status.EXPIRED,"ROOT_DEADLINE"));
        assertThrows(IllegalArgumentException.class,()->store.scanActive(0));
        assertThrows(IllegalArgumentException.class,()->store.scanActive(129));
    }
    @Test void expirySweepPreservesUnknownCostAndLeavesOtherStatesUntouched() {
        var d=dispatchable();assertTrue(store.beginWorkDispatch(d.call().id(),d.lease()));assertTrue(store.markUnknown(d.call().id()));
        var db=new JdbcTemplate(source);
        db.update("UPDATE agent_video_notes_job SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=?",d.job().id().toString());
        String originalUser=user;user=UUID.randomUUID().toString();var ready=store.create(request("ready"));
        db.update("UPDATE agent_video_notes_job SET status='READY',deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=?",ready.id().toString());
        user=UUID.randomUUID().toString();var active=store.create(request("future"));
        assertEquals(1,store.expireActive(128));assertEquals(0,store.expireActive(128));
        var expired=store.get(d.job().id(),"site",originalUser);
        assertEquals(Status.EXPIRED,expired.status());assertEquals(d.job().version()+1,expired.version());
        assertEquals(70,expired.reservedMicros());assertTrue(store.hasUnknownCalls(d.job().id(),"site",originalUser));
        assertEquals("NOTES_EXPIRED",store.errorCode(d.job().id(),"site",originalUser));
        assertEquals(Status.READY,store.get(ready.id(),"site",ready.request().userId()).status());
        assertEquals(Status.QUEUED,store.get(active.id(),"site",user).status());
        assertThrows(IllegalArgumentException.class,()->store.expireActive(0));
    }
    @Test void enqueueAndBindCommitAtomicallyAndReplayReturnsSameWork() {
        var job=running();var unit=store.dependencies(job.id(),"site",user).get(0).unit();
        var grant=new ContentWork.Grant(UUID.randomUUID(),job.request().asset(),Instant.now().plusSeconds(600));
        var deadline=Instant.now().plusSeconds(300);
        var work=store.enqueueBound(job.id(),"site",user,job.version(),1,unit.unitKey(),grant,deadline,3).orElseThrow();
        assertEquals(work.id(),store.enqueueBound(job.id(),"site",user,job.version(),1,unit.unitKey(),grant,deadline,3).orElseThrow().id());
        assertEquals(work.id(),store.dependencies(job.id(),"site",user).stream().filter(d->d.unit().equals(unit)).findFirst().orElseThrow().workId());
        assertTrue(store.enqueueBound(job.id(),"site",user,job.version()+1,1,unit.unitKey(),grant,deadline,3).isEmpty());
    }
    @Test void failedBindingRollsBackWorkAdmission() {
        var job=running();var unit=store.dependencies(job.id(),"site",user).get(0).unit();var db=new JdbcTemplate(source);
        db.update("UPDATE agent_video_notes_dependency SET work_id=? WHERE root_id=? AND unit_key=?",UUID.randomUUID().toString(),job.id().toString(),unit.unitKey());
        var grant=new ContentWork.Grant(UUID.randomUUID(),job.request().asset(),Instant.now().plusSeconds(600));
        assertThrows(RuntimeException.class,()->store.enqueueBound(job.id(),"site",user,job.version(),1,unit.unitKey(),grant,Instant.now().plusSeconds(300),3));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_content_work",Integer.class));
    }
    @Test void externalWorkerCannotObserveWorkBeforeItsBindingTransactionCommits() throws Exception {
        var job=running();var unit=store.dependencies(job.id(),"site",user).get(0).unit();
        var grant=new ContentWork.Grant(UUID.randomUUID(),job.request().asset(),Instant.now().plusSeconds(600));
        var deadline=Instant.now().plusSeconds(300);var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).execute(status->{
                var work=store.enqueueBound(job.id(),"site",user,job.version(),1,unit.unitKey(),grant,deadline,3).orElseThrow();entered.countDown();
                try {if(!finish.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return work.id();
            }));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals(0,new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM agent_content_work WHERE status='QUEUED'",Integer.class));
            finish.countDown();UUID workId=future.get(10,TimeUnit.SECONDS);
            assertEquals(workId,store.dependencies(job.id(),"site",user).stream().filter(d->d.workId()!=null).findFirst().orElseThrow().workId());
        }finally{finish.countDown();pool.shutdownNow();}
    }
    private void completeMaterial(Job root,PlanUnit unit,UUID workId,String reference,String digest) {
        new JdbcTemplate(source).update("UPDATE agent_content_work SET status='RUNNING',fence=1,lease_owner='notes-it',lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)) WHERE job_id=?",workId.toString());
        var lease=new ContentWork.Lease(workId,1,"notes-it");
        var call=store.prepareCall(root.id(),"site",user,root.generation(),unit.segmentId(root.id()),1);
        assertTrue(store.beginWorkDispatch(call.id(),lease));assertTrue(store.stageWorkResult(call.id(),lease,reference,digest));assertTrue(store.commitCall(call.id(),1));
        assertTrue(new MysqlContentWorkStore(source,new ObjectMapper()).complete(lease,new ContentWork.Receipt(reference,digest)));
    }
    private Job completeSourcePlan() {
        var root=running();
        for(var dep:store.dependencies(root.id(),"site",user)) {
            var grant=new ContentWork.Grant(UUID.randomUUID(),root.request().asset(),Instant.now().plusSeconds(600));
            var work=store.enqueueBound(root.id(),"site",user,root.version(),root.generation(),dep.unit().unitKey(),grant,Instant.now().plusSeconds(300),3).orElseThrow();
            completeMaterial(root,dep.unit(),work.id(),"source-"+NotesJob.hash(dep.unit().unitKey()),"a".repeat(64));
        }
        return store.get(root.id(),"site",user);
    }
    private ContentWork.Job enqueueSummary(Job root,List<MysqlNotesJobStore.SourceToken> tokens) {
        return store.enqueueSynthesisBound(root.id(),"site",user,root.version(),root.generation(),new ContentWork.Grant(UUID.randomUUID(),root.request().asset(),Instant.now().plusSeconds(600)),Instant.now().plusSeconds(300),3,tokens).orElseThrow();
    }
    private void completeSummary(Job root,PlanUnit unit,UUID workId,NotesDocumentObjects.Reference reference,NotesDocument.QualityReceipt quality) {
        new JdbcTemplate(source).update("UPDATE agent_content_work SET status='RUNNING',fence=1,lease_owner='notes-it',lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)) WHERE job_id=?",workId.toString());
        var lease=new ContentWork.Lease(workId,1,"notes-it");
        for(String phase:List.of("draft","check")) {
            var call=store.prepareCall(root.id(),"site",user,root.generation(),unit.segmentId(root.id())+":"+phase,1);
            assertTrue(store.beginWorkDispatch(call.id(),lease));
            assertTrue(store.stageWorkResult(call.id(),lease,(phase.equals("draft")?"notes-draft:":"notes-quality:")+reference.documentDigest(),phase.equals("draft")?reference.documentDigest():MysqlNotesJobStore.qualityReceiptDigest(quality)));
            assertTrue(store.commitCall(call.id(),1));
        }
        assertTrue(new MysqlContentWorkStore(source,new ObjectMapper()).complete(lease,new ContentWork.Receipt(reference.objectKey(),reference.sha256())));
    }
    @Test void synthesisUsesCompleteCanonicalSourceSnapshotAndSeparateVersion() {
        var root=completeSourcePlan();var tokens=store.sourceSnapshot(root.id(),"site",user);
        var reversed=new ArrayList<>(tokens);Collections.reverse(reversed);
        assertEquals(MysqlNotesJobStore.sourceSnapshotDigest(tokens),MysqlNotesJobStore.sourceSnapshotDigest(reversed));
        var work=enqueueSummary(root,tokens);assertEquals(ContentWork.Stage.SUMMARY,work.unit().stage());assertEquals(policy.generationVersion(),work.unit().stageVersion());
        assertEquals(work.id(),enqueueSummary(root,tokens).id());
        assertEquals(6,store.dependencies(root.id(),"site",user).size());assertEquals(6,store.plan(root.id(),"site",user).size());
        assertEquals(work.id(),store.synthesisDependency(root.id(),"site",user).orElseThrow().workId());
        assertEquals(root.planDigest(),store.get(root.id(),"site",user).planDigest());
    }
    @Test void incompleteOrChangedSourcesCannotStartSynthesis() {
        var root=running();assertThrows(RuntimeException.class,()->store.sourceSnapshot(root.id(),"site",user));
        user=UUID.randomUUID().toString();var complete=completeSourcePlan();var tokens=store.sourceSnapshot(complete.id(),"site",user);
        var changed=new ArrayList<>(tokens);var first=changed.get(0);changed.set(0,new MysqlNotesJobStore.SourceToken(first.unitKey(),first.workId(),first.receiptRef(),"b".repeat(64)));
        assertThrows(RuntimeException.class,()->enqueueSummary(complete,changed));
        assertTrue(store.synthesisDependency(complete.id(),"site",user).isEmpty());
    }
    @Test void publicationRequiresFinishedSummaryAndMatchingCommittedReceipts() {
        var root=completeSourcePlan();var tokens=store.sourceSnapshot(root.id(),"site",user);var summary=enqueueSummary(root,tokens);
        var reference=new NotesDocumentObjects.Reference("notes-"+"c".repeat(64)+".json",42,"d".repeat(64),"e".repeat(64));
        var quality=new NotesDocument.QualityReceipt(reference.documentDigest(),"f".repeat(64),true,List.of());
        assertFalse(store.publish(root.id(),"site",user,root.version(),1,root.planDigest(),reference,tokens,"f".repeat(64),quality,summary.id()));
        var unit=store.synthesisDependency(root.id(),"site",user).orElseThrow().unit();completeSummary(root,unit,summary.id(),reference,quality);
        assertTrue(store.publish(root.id(),"site",user,root.version(),1,root.planDigest(),reference,tokens,"f".repeat(64),quality,summary.id()));
        var restored=new MysqlNotesJobStore(source,new ObjectMapper());assertEquals(Status.READY,restored.get(root.id(),"site",user).status());
        assertEquals(reference,restored.publishedReference(root.id(),"site",user).orElseThrow());
        assertThrows(RuntimeException.class,()->restored.publishedReference(root.id(),"site","other"));
    }
    @Test void rejectedQualityCanResumeWithoutReprocessingCompletedSources() {
        var root=completeSourcePlan();var tokens=store.sourceSnapshot(root.id(),"site",user);var summary=enqueueSummary(root,tokens);var db=new JdbcTemplate(source);
        db.update("UPDATE agent_content_work SET status='FAILED',attempts=1 WHERE job_id=?",summary.id().toString());
        assertTrue(store.transition(root.id(),"site",user,root.version(),Status.PARTIAL,"NOTES_QUALITY_REJECTED"));var partial=store.get(root.id(),"site",user);
        var grant=new ContentWork.Grant(UUID.randomUUID(),root.request().asset(),Instant.now().plusSeconds(600));
        assertTrue(store.retryQuality(root.id(),"site",user,partial.version(),summary.id(),grant,Instant.now().plusSeconds(300),1));
        assertFalse(store.retryQuality(root.id(),"site",user,partial.version(),summary.id(),grant,Instant.now().plusSeconds(300),1));
        assertEquals(Status.RUNNING,store.get(root.id(),"site",user).status());assertEquals(tokens,store.sourceSnapshot(root.id(),"site",user));
    }
    @Test void publicationAcceptsAccountedUnknownAndRepairedReceipts() {
        var root=completeSourcePlan();var tokens=store.sourceSnapshot(root.id(),"site",user);var summary=enqueueSummary(root,tokens);
        var reference=new NotesDocumentObjects.Reference("notes-"+"c".repeat(64)+".json",42,"d".repeat(64),"e".repeat(64));
        var quality=new NotesDocument.QualityReceipt(reference.documentDigest(),"f".repeat(64),true,List.of());
        var unit=store.synthesisDependency(root.id(),"site",user).orElseThrow().unit();completeSummary(root,unit,summary.id(),reference,quality);
        var db=new JdbcTemplate(source);db.update("INSERT INTO agent_video_notes_call(call_id,root_id,generation,operation_key,state,reserved_micros,actual_micros) VALUES(?,?,1,'previous-unknown','UNKNOWN_CHARGED',1,1)",UUID.randomUUID().toString(),root.id().toString());
        for(String phase:List.of("repair","recheck")) {
            String ref=(phase.equals("repair")?"notes-draft:":"notes-quality:")+reference.documentDigest();
            String digest=phase.equals("repair")?reference.documentDigest():MysqlNotesJobStore.qualityReceiptDigest(quality);
            db.update("INSERT INTO agent_video_notes_call(call_id,root_id,generation,operation_key,state,reserved_micros,actual_micros,work_id,result_ref,result_digest) VALUES(?,?,1,?,'COMMITTED',1,1,?,?,?)",UUID.randomUUID().toString(),root.id().toString(),unit.segmentId(root.id())+":"+phase,summary.id().toString(),ref,digest);
        }
        assertTrue(store.publish(root.id(),"site",user,root.version(),1,root.planDigest(),reference,tokens,"f".repeat(64),quality,summary.id()));
    }
    @Test void publicationRejectsUnsettledCallOrStaleSourceEvenAfterSummarySuccess() {
        var root=completeSourcePlan();var tokens=store.sourceSnapshot(root.id(),"site",user);var summary=enqueueSummary(root,tokens);
        var reference=new NotesDocumentObjects.Reference("notes-"+"c".repeat(64)+".json",42,"d".repeat(64),"e".repeat(64));
        var quality=new NotesDocument.QualityReceipt(reference.documentDigest(),"f".repeat(64),true,List.of());
        completeSummary(root,store.synthesisDependency(root.id(),"site",user).orElseThrow().unit(),summary.id(),reference,quality);
        var pending=store.prepareCall(root.id(),"site",user,1,"pending-extra",1);
        assertFalse(store.publish(root.id(),"site",user,root.version(),1,root.planDigest(),reference,tokens,"f".repeat(64),quality,summary.id()));
        // Isolate the stale-source gate from the earlier pending-reservation gate. This fixture
        // deletes an explicitly never-dispatched call; production has no such refund shortcut.
        new JdbcTemplate(source).update("DELETE FROM agent_video_notes_call WHERE call_id=? AND state='PREPARED'",pending.id().toString());
        new JdbcTemplate(source).update("UPDATE agent_video_notes_job SET reserved_micros=0 WHERE job_id=?",root.id().toString());
        new JdbcTemplate(source).update("UPDATE agent_content_work SET result_digest=? WHERE job_id=?","b".repeat(64),tokens.get(0).workId().toString());
        assertThrows(RuntimeException.class,()->store.publish(root.id(),"site",user,root.version(),1,root.planDigest(),reference,tokens,"f".repeat(64),quality,summary.id()));
        assertEquals(Status.RUNNING,store.get(root.id(),"site",user).status());assertTrue(store.publishedReference(root.id(),"site",user).isEmpty());
    }
    @Test void automaticSynthesisPublishesWithRealStoresAndMockModelsWithoutHttp() throws Exception { automaticSynthesis(false,false); }
    @Test void automaticRepairPublishesOnlyAfterIndependentRecheck() throws Exception { automaticSynthesis(true,false); }
    @Test void disputedRecheckRequiresAnAdditionalGroundedVerdict() throws Exception { automaticSynthesis(true,true); }
    private void automaticSynthesis(boolean repair,boolean arbitrate) throws Exception {
        var root=running();var json=new ObjectMapper();var works=new MysqlContentWorkStore(source,json);
        var sourceObjects=new FileNotesSourceStore(files.resolve("sources"),json);
        for(var plan:store.plan(root.id(),"site",user)) {
            var grant=new ContentWork.Grant(UUID.randomUUID(),root.request().asset(),Instant.now().plusSeconds(600));
            var job=store.enqueueBound(root.id(),"site",user,root.version(),root.generation(),plan.unitKey(),grant,Instant.now().plusSeconds(300),2).orElseThrow();
            var lease=works.claim("notes-source-it",Duration.ofSeconds(60),plan.stage(),policy.analysisVersion()).orElseThrow();job=works.requireLease(lease);
            assertTrue(store.bindWork(root.id(),"site",user,root.generation(),plan.unitKey(),job.id()));
            var call=store.prepareCall(root.id(),"site",user,root.generation(),plan.segmentId(root.id()),1);assertTrue(store.beginWorkDispatch(call.id(),lease));
            var transcript=plan.stage()==ContentWork.Stage.TRANSCRIBE?new com.tianmu.agent.video.content.TimedTranscript(plan.dependencyStartMs(),plan.dependencyEndMs(),"课程片段",com.tianmu.agent.video.content.TimedTranscript.State.AVAILABLE,com.tianmu.agent.video.content.TimedTranscript.Precision.CLIP,List.of(new com.tianmu.agent.video.content.TimedTranscript.Segment(plan.dependencyStartMs(),plan.dependencyEndMs(),"课程片段"))):null;
            var visual=plan.stage()==ContentWork.Stage.VISION?new NotesSourceProcessor.Visual("板书内容",List.of(new com.tianmu.agent.core.VideoContent.Moment(plan.startMs(),"可见板书")),List.of(plan.startMs())):null;
            var receipt=sourceObjects.save(job,lease,new NotesSourceProcessor.Evidence(job.unit(),transcript,visual));
            assertTrue(store.stageWorkResult(call.id(),lease,receipt.reference(),receipt.digest()));assertTrue(store.commitCall(call.id(),1));assertTrue(works.complete(lease,receipt));
        }
        var processing=new NotesCoordinator.ProcessingGrants(){
            public void requireRoot(Job job,com.tianmu.agent.runtime.control.CallControl control){control.checkpoint();}
            public ContentWork.Grant issue(Job job,PlanUnit unit,UUID requestId,com.tianmu.agent.runtime.control.CallControl control){return new ContentWork.Grant(requestId,job.request().asset(),Instant.now().plusSeconds(600));}
        };
        var authority=new com.tianmu.agent.video.content.ContentWorkService(works,(grant,checkpoint,control)->control.checkpoint(),Duration.ofSeconds(1));
        var materials=new NotesMaterialReader(store,works,sourceObjects,processing,100,100000);
        var objectStore=new com.tianmu.agent.adapters.storage.FileObjectStore(files.resolve("documents"));
        var documents=new NotesDocumentObjects(objectStore,json);var checkpoints=new NotesSynthesisObjects(objectStore,json);
        var modelCalls=new java.util.concurrent.atomic.AtomicInteger();
        var model=new NotesModelPort(){
            public String version(){return policy.generationVersion();}
            public com.fasterxml.jackson.databind.JsonNode request(Operation op,Map<String,Object> data,int tokens,com.tianmu.agent.runtime.control.CallControl control){
                control.beforeDispatch();modelCalls.incrementAndGet();
                if(op==Operation.CHECK)return json.valueToTree(repair?Map.of("passed",false,"majorIssues",List.of("需要更正")):Map.of("passed",true,"majorIssues",List.of()));
                if(op==Operation.RECHECK)return json.valueToTree(arbitrate?Map.of("passed",false,"majorIssues",List.of("需要核实")):Map.of("passed",true,"majorIssues",List.of()));
                if(op==Operation.ARBITRATE)return json.valueToTree(Map.of("passed",true,"majorIssues",List.of()));
                var coverage=(NotesCoverage)data.get("coverage");
                var sections=coverage.units().stream().map(u->Map.of("title","片段笔记","blocks",List.of(Map.of("kind","PARAGRAPH","text","本段包含课程内容。","items",List.of(),"rows",List.of(),"citations",List.of(Map.of("unitId",u.id(),"modality","AUDIO")))),"uncertainties",List.of())).toList();
                return json.valueToTree(Map.of("title","课程笔记","overview","课程内容整理","keyPoints",List.of(),"sections",sections,"conclusions",List.of()));
            }
        };
        var synthesis=new NotesSynthesisService(model,json,100000,4096);
        var reader=new NotesAdmissionService((video,viewer,control)->new com.tianmu.agent.video.content.ContentReadAuthority.Identity(root.request().asset(),root.request().durationMs()),store,policy,1800000,Duration.ofHours(1),java.time.Clock.systemUTC());
        var publication=new NotesPublicationService(store,documents,processing,reader,synthesis.qualityPolicyDigest());
        try(var worker=new NotesSynthesisWorker(store,works,authority,materials,synthesis,checkpoints,documents,publication,processing,"notes-summary-it",Duration.ofSeconds(60),Duration.ofSeconds(5),1)) {
            var coordinator=new NotesSynthesisCoordinator(store,works,authority,processing,worker,Duration.ofSeconds(300));
            var control=new com.tianmu.agent.runtime.control.CallControl(Instant.now().plusSeconds(120),new com.tianmu.agent.runtime.control.CancellationToken());
            assertEquals(NotesSynthesisCoordinator.Outcome.ENQUEUED,coordinator.advance(root.id(),"site",user,control));
            assertEquals(NotesLeaseRunner.Outcome.SUCCEEDED,worker.runOnce(control));
            assertEquals(Status.READY,store.get(root.id(),"site",user).status());assertEquals(arbitrate?5:repair?4:2,modelCalls.get());
            var viewer=new com.tianmu.agent.core.AgentTypes.AccessScope("site",user,Set.of(),"",Set.of(root.request().asset().videoId()));
            assertEquals("课程笔记",publication.read(root.id(),viewer,control).title());assertTrue(publication.copy(root.id(),viewer,control).contains("本段包含课程内容"));
        }
    }
    @Test void renewalIsAtomicAgainstClaimAndCannotReplaceAClaimedGrant() {
        var root=running();var unit=store.plan(root.id(),"site",user).get(0);var works=new MysqlContentWorkStore(source,new ObjectMapper());
        var grant=new ContentWork.Grant(UUID.randomUUID(),root.request().asset(),Instant.now().plusSeconds(600));
        var work=store.enqueueBound(root.id(),"site",user,root.version(),root.generation(),unit.unitKey(),grant,Instant.now().plusSeconds(120),2).orElseThrow();
        var renewed=new ContentWork.Grant(UUID.randomUUID(),root.request().asset(),Instant.now().plusSeconds(600));
        assertTrue(works.renewUnclaimed(work.id(),grant.id(),work.fence(),renewed,Instant.now().plusSeconds(120)));
        assertFalse(works.renewUnclaimed(work.id(),grant.id(),work.fence(),renewed,Instant.now().plusSeconds(120)));
        var lease=works.claim("renew-it",Duration.ofSeconds(60),unit.stage(),policy.analysisVersion()).orElseThrow();
        var claimed=works.requireLease(lease);assertEquals(renewed.id(),claimed.grant().id());
        var next=new ContentWork.Grant(UUID.randomUUID(),root.request().asset(),Instant.now().plusSeconds(600));
        assertFalse(works.renewUnclaimed(work.id(),renewed.id(),claimed.fence(),next,Instant.now().plusSeconds(120)));
    }
    @Test void synthesisCheckpointMetadataAndReceiptAreAtomicAndRecoverAfterReopeningStore() {
        var root=completeSourcePlan();var tokens=store.sourceSnapshot(root.id(),"site",user);var work=enqueueSummary(root,tokens);
        var unit=store.synthesisDependency(root.id(),"site",user).orElseThrow().unit();var db=new JdbcTemplate(source);
        db.update("UPDATE agent_content_work SET status='RUNNING',fence=1,lease_owner='notes-it',lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)) WHERE job_id=?",work.id().toString());
        var lease=new ContentWork.Lease(work.id(),1,"notes-it");
        var draft=store.prepareCall(root.id(),"site",user,1,unit.segmentId(root.id())+":draft",1);
        assertTrue(store.beginWorkDispatch(draft.id(),lease));
        var reference=new NotesSynthesisObjects.Reference("notes-step-"+"a".repeat(64)+".json",100,"b".repeat(64));
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status->{assertTrue(store.stageSynthesisResult(draft.id(),lease,"notes-draft:"+"e".repeat(64),"e".repeat(64),reference));status.setRollbackOnly();});
        assertTrue(store.synthesisCheckpoint(draft.id()).isEmpty());
        assertEquals(CallState.DISPATCHING,store.call(root.id(),"site",user,1,draft.operationKey()).orElseThrow().state());
        assertFalse(store.stageSynthesisResult(draft.id(),new ContentWork.Lease(work.id(),2,"notes-it"),"notes-draft:"+"e".repeat(64),"e".repeat(64),reference));
        assertTrue(store.stageSynthesisResult(draft.id(),lease,"notes-draft:"+"e".repeat(64),"e".repeat(64),reference));
        assertTrue(store.commitCall(draft.id(),1));
        var reopened=new MysqlNotesJobStore(source,new ObjectMapper());
        assertEquals(reference,reopened.synthesisCheckpoint(draft.id()).orElseThrow());
        assertEquals(CallState.COMMITTED,reopened.call(root.id(),"site",user,1,draft.operationKey()).orElseThrow().state());
    }
    @Test void summaryCheckCannotDispatchBeforeDraftAndBothReceiptsAreRequiredToPublish() {
        var root=completeSourcePlan();var tokens=store.sourceSnapshot(root.id(),"site",user);var work=enqueueSummary(root,tokens);
        var unit=store.synthesisDependency(root.id(),"site",user).orElseThrow().unit();var db=new JdbcTemplate(source);
        db.update("UPDATE agent_content_work SET status='RUNNING',fence=1,lease_owner='notes-it',lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)) WHERE job_id=?",work.id().toString());
        var lease=new ContentWork.Lease(work.id(),1,"notes-it");
        var check=store.prepareCall(root.id(),"site",user,1,unit.segmentId(root.id())+":check",1);
        assertFalse(store.beginWorkDispatch(check.id(),lease));
        var draft=store.prepareCall(root.id(),"site",user,1,unit.segmentId(root.id())+":draft",1);
        assertTrue(store.beginWorkDispatch(draft.id(),lease));assertFalse(store.beginWorkDispatch(check.id(),lease));
        assertTrue(store.stageWorkResult(draft.id(),lease,"notes-draft:"+"e".repeat(64),"e".repeat(64)));assertTrue(store.commitCall(draft.id(),1));
        assertTrue(store.beginWorkDispatch(check.id(),lease));
        var reference=new NotesDocumentObjects.Reference("notes-"+"c".repeat(64)+".json",42,"d".repeat(64),"e".repeat(64));
        var quality=new NotesDocument.QualityReceipt(reference.documentDigest(),"f".repeat(64),true,List.of());
        // Even a succeeded work cannot publish while its check call is unresolved.
        db.update("UPDATE agent_content_work SET status='SUCCEEDED',result_ref=?,result_digest=? WHERE job_id=?",reference.objectKey(),reference.sha256(),work.id().toString());
        assertFalse(store.publish(root.id(),"site",user,root.version(),1,root.planDigest(),reference,tokens,"f".repeat(64),quality,work.id()));
        db.update("UPDATE agent_video_notes_call SET state='COMMITTED',result_ref=?,result_digest=?,actual_micros=1 WHERE call_id=?","notes-quality:"+reference.documentDigest(),MysqlNotesJobStore.qualityReceiptDigest(quality),check.id().toString());
        db.update("UPDATE agent_video_notes_job SET reserved_micros=0,used_micros=used_micros+1 WHERE job_id=?",root.id().toString());
        // Missing the draft's persisted semantic receipt still fails despite a committed check.
        db.update("UPDATE agent_video_notes_call SET result_digest=? WHERE call_id=?","b".repeat(64),draft.id().toString());
        assertFalse(store.publish(root.id(),"site",user,root.version(),1,root.planDigest(),reference,tokens,"f".repeat(64),quality,work.id()));
    }
}
