package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.tianmu.agent.video.content.ContentWork;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.tianmu.agent.video.notes.NotesJob.*;

/** Explicitly assembled persistence foundation. Never dispatches a model or authorizes a reader. */
public final class MysqlNotesJobStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final com.tianmu.agent.video.content.MysqlContentWorkStore contentWorks;
    public MysqlNotesJobStore(DataSource source, ObjectMapper json) {
        db = new JdbcTemplate(source); db.setQueryTimeout(5);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.json = json.copy().findAndRegisterModules();
        contentWorks=new com.tianmu.agent.video.content.MysqlContentWorkStore(source,this.json);
    }
    public Job create(Create request) {
        if (!request.deadline().isAfter(Instant.now())) throw error("NOTES_EXPIRED");
        String root = rootKey(request);
        String requestDigest = hash(root);
        return tx.execute(status -> {
            UUID proposed = UUID.randomUUID();
            db.update("INSERT INTO agent_video_notes_job(job_id,root_key,tenant_id,user_id,request_json,status,budget_micros,deadline_at) VALUES(?,?,?,?,?,'QUEUED',?,?) ON DUPLICATE KEY UPDATE root_key=root_key",
                    proposed.toString(),root,request.tenantId(),request.userId(),encode(request),request.policy().budgetMicros(),Timestamp.from(request.deadline()));
            UUID id = UUID.fromString(db.queryForObject("SELECT job_id FROM agent_video_notes_job WHERE root_key=?",String.class,root));
            db.update("INSERT INTO agent_video_notes_binding(tenant_id,user_id,idempotency_key,request_digest,job_id) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE idempotency_key=idempotency_key",
                    request.tenantId(),request.userId(),request.idempotencyKey(),requestDigest,id.toString());
            var binding = db.queryForMap("SELECT request_digest,job_id FROM agent_video_notes_binding WHERE tenant_id=? AND user_id=? AND idempotency_key=?",
                    request.tenantId(),request.userId(),request.idempotencyKey());
            if (!requestDigest.equals(binding.get("request_digest"))) throw error("NOTES_IDEMPOTENCY_CONFLICT");
            return get(UUID.fromString(binding.get("job_id").toString()),request.tenantId(),request.userId());
        });
    }
    public Job get(UUID id,String tenant,String user) {
        text(tenant); text(user);
        var rows=db.queryForList("SELECT * FROM agent_video_notes_job WHERE job_id=? AND tenant_id=? AND user_id=?",id.toString(),tenant,user);
        if(rows.size()!=1) throw error("NOTES_NOT_FOUND");
        var row=rows.get(0);
        return new Job(id,decode(row.get("request_json"),Create.class),row.get("root_key").toString(),Status.valueOf(row.get("status").toString()),
                number(row,"version"),((Number)row.get("generation")).intValue(),(String)row.get("plan_digest"),number(row,"reserved_micros"),number(row,"used_micros"),(String)row.get("document_digest"));
    }
    public Optional<Job> find(Create request) {
        return db.queryForList("SELECT job_id FROM agent_video_notes_job WHERE root_key=? AND tenant_id=? AND user_id=?",
                rootKey(request),request.tenantId(),request.userId()).stream().findFirst()
                .map(row->get(UUID.fromString(row.get("job_id").toString()),request.tenantId(),request.userId()));
    }
    /** Internal bounded recovery discovery, never a reader authorization or a worker queue. */
    public List<Job> scanActive(int limit) {
        if(limit<1 || limit>128)throw new IllegalArgumentException("Invalid scan limit");
        return db.queryForList("SELECT job_id,tenant_id,user_id FROM agent_video_notes_job WHERE status IN ('QUEUED','RUNNING') AND deadline_at>UTC_TIMESTAMP(6) ORDER BY deadline_at,job_id LIMIT ?",limit)
                .stream().map(row->get(UUID.fromString(row.get("job_id").toString()),row.get("tenant_id").toString(),row.get("user_id").toString())).toList();
    }
    /** Expiry is a database-time state change; never refunds unresolved provider reservations. */
    public int expireActive(int limit) {
        if(limit<1 || limit>128)throw new IllegalArgumentException("Invalid expiry limit");
        return db.update("UPDATE agent_video_notes_job SET status='EXPIRED',error_code='NOTES_EXPIRED',version=version+1 WHERE status IN ('QUEUED','RUNNING') AND deadline_at<=UTC_TIMESTAMP(6) ORDER BY deadline_at,job_id LIMIT ?",limit);
    }
    public record Dependency(PlanUnit unit, UUID workId) {}
    public record SourceToken(String unitKey,UUID workId,String receiptRef,String receiptDigest) {
        public SourceToken {text(unitKey);Objects.requireNonNull(workId);text(receiptRef);digest(receiptDigest);}
    }
    public static String sourceSnapshotDigest(List<SourceToken> tokens) {
        List<String> fields=new ArrayList<>();var keys=new HashSet<String>();
        for(var token:tokens.stream().sorted(Comparator.comparing(SourceToken::unitKey)).toList()) {
            if(!keys.add(token.unitKey()))throw new IllegalArgumentException("Duplicate source token");
            fields.add(token.unitKey());fields.add(token.workId().toString());fields.add(token.receiptRef());fields.add(token.receiptDigest());
        }
        if(fields.isEmpty())throw new IllegalArgumentException("Empty source snapshot");
        return hash(fields.toArray(String[]::new));
    }
    public List<SourceToken> sourceSnapshot(UUID id,String tenant,String user) {
        return tx.execute(transaction->{lock(id);return lockedSources(get(id,tenant,user));});
    }
    private List<SourceToken> lockedSources(Job job) {
        var deps=dependencies(job.id(),job.request().tenantId(),job.request().userId());
        validatePlan(job,deps.stream().map(Dependency::unit).toList());
        List<SourceToken> tokens=new ArrayList<>();
        for(var dep:deps) {
            if(dep.workId()==null)throw error("NOTES_MATERIAL_NOT_READY");
            var rows=db.queryForList("SELECT document,status,result_ref,result_digest FROM agent_content_work WHERE job_id=? FOR UPDATE",dep.workId().toString());
            if(rows.size()!=1 || !"SUCCEEDED".equals(rows.get(0).get("status")))throw error("NOTES_MATERIAL_NOT_READY");
            var row=rows.get(0);var unit=decode(row.get("document"),ContentWork.Unit.class);var expected=dep.unit();
            if(!unit.asset().equals(job.request().asset()) || unit.stage()!=expected.stage()
                    || !unit.segmentId().equals(expected.segmentId(job.id())) || !unit.stageVersion().equals(stageVersion(job,expected))
                    || unit.startMs()!=expected.dependencyStartMs() || unit.endMs()!=expected.dependencyEndMs()
                    || row.get("result_ref")==null || row.get("result_digest")==null)throw error("NOTES_WORK_CONFLICT");
            tokens.add(new SourceToken(expected.unitKey(),dep.workId(),row.get("result_ref").toString(),row.get("result_digest").toString()));
        }
        return tokens.stream().sorted(Comparator.comparing(SourceToken::unitKey)).toList();
    }
    public Optional<Dependency> synthesisDependency(UUID id,String tenant,String user) {
        Job job=get(id,tenant,user);
        var rows=db.queryForList("SELECT unit_json,work_id FROM agent_video_notes_dependency WHERE root_id=? AND generation=? AND JSON_UNQUOTE(JSON_EXTRACT(unit_json,'$.stage'))='SUMMARY'",id.toString(),job.generation());
        if(rows.size()>1)throw error("NOTES_SYNTHESIS_CONFLICT");
        return rows.stream().findFirst().map(row->new Dependency(decode(row.get("unit_json"),PlanUnit.class),row.get("work_id")==null?null:UUID.fromString(row.get("work_id").toString())));
    }
    public Optional<Dependency> ensureSynthesisUnit(UUID id,String tenant,String user,long expectedVersion,String inputDigest) {
        digest(inputDigest);
        return tx.execute(transaction->{
            lock(id);var job=get(id,tenant,user);
            if(!active(job,expectedVersion) || hasUnknownCalls(id,tenant,user))return Optional.empty();
            if(!sourceSnapshotDigest(lockedSources(job)).equals(inputDigest))throw error("NOTES_SOURCE_CHANGED");
            var previous=synthesisDependency(id,tenant,user);
            if(previous.isPresent()) {
                if(!previous.get().unit().inputDigest().equals(inputDigest))throw error("NOTES_SYNTHESIS_CONFLICT");
                return previous;
            }
            var unit=new PlanUnit("summary:"+inputDigest,ContentWork.Stage.SUMMARY,0,job.request().durationMs(),0,job.request().durationMs(),inputDigest);
            db.update("INSERT INTO agent_video_notes_dependency(root_id,generation,unit_key,unit_json) VALUES(?,?,?,?)",id.toString(),job.generation(),unit.unitKey(),encode(unit));
            return Optional.of(new Dependency(unit,null));
        });
    }
    public Optional<ContentWork.Job> enqueueSynthesisBound(UUID id,String tenant,String user,long expectedVersion,int generation,
            ContentWork.Grant grant,Instant deadline,int maxAttempts,List<SourceToken> expectedSources) {
        String expectedDigest=sourceSnapshotDigest(List.copyOf(expectedSources));
        return tx.execute(transaction->{
            lock(id);var job=get(id,tenant,user);
            if(!active(job,expectedVersion) || generation!=job.generation() || hasUnknownCalls(id,tenant,user))return Optional.empty();
            if(!job.request().asset().equals(grant.asset()) || deadline.isAfter(job.request().deadline()))throw error("NOTES_WORK_CONFLICT");
            if(!sourceSnapshotDigest(lockedSources(job)).equals(expectedDigest))throw error("NOTES_SOURCE_CHANGED");
            var dep=ensureSynthesisUnit(id,tenant,user,expectedVersion,expectedDigest).orElseThrow();var unit=dep.unit();
            var work=contentWorks.enqueue(new ContentWork.Unit(job.request().asset(),ContentWork.Stage.SUMMARY,unit.segmentId(id),stageVersion(job,unit),0,job.request().durationMs()),grant,deadline,maxAttempts);
            if(!bindWork(id,tenant,user,generation,unit.unitKey(),work.id()))throw error("NOTES_WORK_CONFLICT");
            return Optional.of(work);
        });
    }
    /** Internal service-only publication. It cannot authorize a client or substitute a quality evaluator. */
    public boolean publish(UUID id,String tenant,String user,long expectedVersion,int generation,String planDigest,
            NotesDocumentObjects.Reference reference,List<SourceToken> expectedSources,String qualityPolicyDigest,
            NotesDocument.QualityReceipt quality,UUID summaryWorkId) {
        digest(planDigest);digest(qualityPolicyDigest);Objects.requireNonNull(reference);Objects.requireNonNull(quality);
        String sourceDigest=sourceSnapshotDigest(List.copyOf(expectedSources));
        if(!reference.documentDigest().equals(quality.documentDigest()) || !qualityPolicyDigest.equals(quality.policyDigest())
                || !quality.passed() || !quality.majorIssues().isEmpty())throw error("NOTES_QUALITY_REJECTED");
        return tx.execute(transaction->{
            lock(id);var job=get(id,tenant,user);
            if(!active(job,expectedVersion) || job.generation()!=generation || !Objects.equals(job.planDigest(),planDigest) || job.reservedMicros()!=0)return false;
            if(!sourceSnapshotDigest(lockedSources(job)).equals(sourceDigest))throw error("NOTES_SOURCE_CHANGED");
            if(db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND state NOT IN ('COMMITTED','UNKNOWN_CHARGED')",Integer.class,id.toString())!=0)return false;
            var dep=synthesisDependency(id,tenant,user);
            if(dep.isEmpty() || !Objects.equals(dep.get().workId(),summaryWorkId) || !dep.get().unit().inputDigest().equals(sourceDigest))return false;
            var rows=db.queryForList("SELECT document,status,result_ref,result_digest FROM agent_content_work WHERE job_id=? FOR UPDATE",summaryWorkId.toString());
            if(rows.size()!=1)return false;var row=rows.get(0);var unit=decode(row.get("document"),ContentWork.Unit.class);
            if(!"SUCCEEDED".equals(row.get("status")) || !reference.objectKey().equals(row.get("result_ref")) || !reference.sha256().equals(row.get("result_digest"))
                    || unit.stage()!=ContentWork.Stage.SUMMARY || !unit.asset().equals(job.request().asset())
                    || !unit.stageVersion().equals(stageVersion(job,dep.get().unit())) || !unit.segmentId().equals(dep.get().unit().segmentId(id))
                    || unit.startMs()!=0 || unit.endMs()!=job.request().durationMs())return false;
            // Every material and synthesis work must have a committed fenced call; zero-call rows are not publication evidence.
            for(var token:expectedSources)if(db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND generation=? AND work_id=? AND state='COMMITTED' AND result_ref=? AND result_digest=?",Integer.class,id.toString(),generation,token.workId().toString(),token.receiptRef(),token.receiptDigest())!=1)return false;
            String base=dep.get().unit().segmentId(id);
            boolean repaired=db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND generation=? AND work_id=? AND operation_key=? AND state='COMMITTED'",Integer.class,id.toString(),generation,summaryWorkId.toString(),base+":repair")==1;
            boolean arbitrated=db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND generation=? AND work_id=? AND operation_key=? AND state='COMMITTED'",Integer.class,id.toString(),generation,summaryWorkId.toString(),base+":arbitrate")==1;
            String draftOp=base+(repaired?":repair":":draft"),checkOp=base+(arbitrated?":arbitrate":repaired?":recheck":":check");
            if(db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND generation=? AND work_id=? AND operation_key=? AND state='COMMITTED' AND result_ref=? AND result_digest=?",Integer.class,id.toString(),generation,summaryWorkId.toString(),draftOp,"notes-draft:"+reference.documentDigest(),reference.documentDigest())!=1)return false;
            if(db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND generation=? AND work_id=? AND operation_key=? AND state='COMMITTED' AND result_ref=? AND result_digest=?",Integer.class,id.toString(),generation,summaryWorkId.toString(),checkOp,"notes-quality:"+reference.documentDigest(),qualityReceiptDigest(quality))!=1)return false;
            db.update("INSERT INTO agent_video_notes_document(root_id,generation,reference_json,document_digest,plan_digest,source_snapshot_digest,quality_policy_digest,quality_json,summary_work_id) VALUES(?,?,?,?,?,?,?,?,?)",
                    id.toString(),generation,encode(reference),reference.documentDigest(),planDigest,sourceDigest,qualityPolicyDigest,encode(quality),summaryWorkId.toString());
            return db.update("UPDATE agent_video_notes_job SET status='READY',version=version+1,document_digest=?,error_code=NULL WHERE job_id=? AND version=? AND generation=? AND status='RUNNING'",reference.documentDigest(),id.toString(),expectedVersion,generation)==1;
        });
    }
    public Optional<NotesDocumentObjects.Reference> publishedReference(UUID id,String tenant,String user) {
        Job job=get(id,tenant,user);if(job.status()!=Status.READY)return Optional.empty();
        var rows=db.queryForList("SELECT reference_json FROM agent_video_notes_document WHERE root_id=? AND generation=? AND document_digest=?",id.toString(),job.generation(),job.documentDigest());
        return rows.stream().findFirst().map(row->decode(row.get("reference_json"),NotesDocumentObjects.Reference.class));
    }
    private boolean active(Job job,long expectedVersion) {
        return job.status()==Status.RUNNING && job.version()==expectedVersion
                && db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_job WHERE job_id=? AND deadline_at>UTC_TIMESTAMP(6)",Integer.class,job.id().toString())==1;
    }
    /** Admission authority is checked by the caller before entering this database-only transaction. */
    public Optional<ContentWork.Job> enqueueBound(UUID id,String tenant,String user,long expectedVersion,int generation,
                                                String unitKey,ContentWork.Grant grant,Instant deadline,int maxAttempts) {
        text(unitKey);Objects.requireNonNull(grant);Objects.requireNonNull(deadline);
        return tx.execute(transaction -> {
            lock(id);Job job=get(id,tenant,user);
            if(job.version()!=expectedVersion || job.generation()!=generation || job.status()!=Status.RUNNING
                    || db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_job WHERE job_id=? AND deadline_at>UTC_TIMESTAMP(6)",Integer.class,id.toString())!=1)return Optional.empty();
            if(hasUnknownCalls(id,tenant,user))return Optional.empty();
            if(!job.request().asset().equals(grant.asset()) || deadline.isAfter(job.request().deadline()))throw error("NOTES_WORK_CONFLICT");
            var rows=db.queryForList("SELECT unit_json FROM agent_video_notes_dependency WHERE root_id=? AND generation=? AND unit_key=?",id.toString(),generation,unitKey);
            if(rows.size()!=1)return Optional.empty();
            var unit=decode(rows.get(0).get("unit_json"),PlanUnit.class);
            var work=contentWorks.enqueue(new ContentWork.Unit(job.request().asset(),unit.stage(),unit.segmentId(id),job.request().policy().analysisVersion(),unit.dependencyStartMs(),unit.dependencyEndMs()),grant,deadline,maxAttempts);
            if(!bindWork(id,tenant,user,generation,unitKey,work.id()))throw error("NOTES_WORK_CONFLICT");
            return Optional.of(work);
        });
    }
    public List<Dependency> dependencies(UUID id,String tenant,String user) {
        Job job=get(id,tenant,user);
        return db.queryForList("SELECT unit_json,work_id FROM agent_video_notes_dependency WHERE root_id=? AND generation=? AND JSON_UNQUOTE(JSON_EXTRACT(unit_json,'$.stage'))<>'SUMMARY' ORDER BY unit_key",id.toString(),job.generation())
                .stream().map(row->new Dependency(decode(row.get("unit_json"),PlanUnit.class),row.get("work_id")==null?null:UUID.fromString(row.get("work_id").toString()))).toList();
    }
    /** Safe degradation only. READY and retries require separate quality/authority protocols. */
    public boolean transition(UUID id,String tenant,String user,long expectedVersion,Status target,String errorCode) {
        Objects.requireNonNull(target);text(errorCode);
        if(expectedVersion<0 || !Set.of(Status.PARTIAL,Status.FAILED,Status.EXPIRED,Status.REVOKED,Status.STALE).contains(target))
            throw new IllegalArgumentException("Invalid notes transition");
        get(id,tenant,user);
        return db.update("UPDATE agent_video_notes_job SET status=?,error_code=?,version=version+1 WHERE job_id=? AND tenant_id=? AND user_id=? AND version=? AND status IN ('QUEUED','RUNNING') AND (?<>'EXPIRED' OR deadline_at<=UTC_TIMESTAMP(6))",
                target.name(),errorCode,id.toString(),tenant,user,expectedVersion,target.name())==1;
    }
    public String errorCode(UUID id,String tenant,String user) {
        get(id,tenant,user);
        return db.queryForObject("SELECT error_code FROM agent_video_notes_job WHERE job_id=? AND tenant_id=? AND user_id=?",String.class,id.toString(),tenant,user);
    }
    /** Old UNKNOWN attempts retain cost and block coordination until explicitly reconciled. */
    public boolean hasUnknownCalls(UUID id,String tenant,String user) {
        get(id,tenant,user);
        Integer count=db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND state='UNKNOWN'",Integer.class,id.toString());
        return count!=null && count>0;
    }
    /** Explicit retry: account for an uncertain provider call before permitting a fresh attempt. */
    public boolean retryUnknown(UUID id,String tenant,String user,long expectedVersion,UUID workId,
                                ContentWork.Grant grant,Instant deadline) {
        return tx.execute(transaction->{
            lock(id);var root=get(id,tenant,user);
            if(root.status()!=Status.PARTIAL || root.version()!=expectedVersion || !root.request().deadline().isAfter(Instant.now())
                    || !root.request().asset().equals(grant.asset()) || deadline.isAfter(root.request().deadline()))return false;
            var unknown=db.queryForList("SELECT call_id,operation_key,reserved_micros,work_id FROM agent_video_notes_call WHERE root_id=? AND generation=? AND state='UNKNOWN' FOR UPDATE",id.toString(),root.generation());
            if(unknown.size()!=1 || !workId.toString().equals(Objects.toString(unknown.get(0).get("work_id"))))return false;
            var old=unknown.get(0);var work=contentWorks.get(workId);
            if(work.status()!=ContentWork.Status.FAILED || work.attempts()>=3 || !work.unit().asset().equals(root.request().asset()))return false;
            long cost=((Number)old.get("reserved_micros")).longValue();
            long budget=Objects.requireNonNull(db.queryForObject("SELECT budget_micros FROM agent_video_notes_job WHERE job_id=?",Long.class,id.toString()));
            if(cost>budget-root.usedMicros()-root.reservedMicros())return false;
            if(!contentWorks.requeue(workId,grant,deadline,1)){transaction.setRollbackOnly();return false;}
            String archived=old.get("operation_key")+":unknown:"+old.get("call_id");text(archived);
            if(db.update("UPDATE agent_video_notes_call SET state='UNKNOWN_CHARGED',operation_key=?,actual_micros=? WHERE call_id=? AND state='UNKNOWN'",archived,cost,old.get("call_id"))!=1
                    || db.update("UPDATE agent_video_notes_job SET status='RUNNING',error_code=NULL,reserved_micros=reserved_micros-?,used_micros=used_micros+?,version=version+1 WHERE job_id=? AND version=? AND status='PARTIAL' AND reserved_micros>=?",cost,cost,id.toString(),expectedVersion,cost)!=1){
                transaction.setRollbackOnly();return false;
            }
            return true;
        });
    }
    public boolean retryQuality(UUID id,String tenant,String user,long expectedVersion,UUID workId,ContentWork.Grant grant,Instant deadline,long phaseCost) {
        return tx.execute(transaction->{
            lock(id);var root=get(id,tenant,user);
            if(root.status()!=Status.PARTIAL || root.version()!=expectedVersion || !"NOTES_QUALITY_REJECTED".equals(errorCode(id,tenant,user))
                    || hasUnknownCalls(id,tenant,user) || !root.request().deadline().isAfter(Instant.now())
                    || !root.request().asset().equals(grant.asset()) || deadline.isAfter(root.request().deadline()))return false;
            var dependency=synthesisDependency(id,tenant,user);var task=contentWorks.get(workId);
            if(dependency.isEmpty() || !workId.equals(dependency.get().workId()) || task.status()!=ContentWork.Status.FAILED || task.attempts()>=4)return false;
            long budget=Objects.requireNonNull(db.queryForObject("SELECT budget_micros FROM agent_video_notes_job WHERE job_id=?",Long.class,id.toString()));
            if(phaseCost<1 || phaseCost>(budget-root.usedMicros()-root.reservedMicros())/2)return false;
            if(!contentWorks.requeue(workId,grant,deadline,1)){transaction.setRollbackOnly();return false;}
            if(db.update("UPDATE agent_video_notes_job SET status='RUNNING',error_code=NULL,version=version+1 WHERE job_id=? AND version=? AND status='PARTIAL'",id.toString(),expectedVersion)!=1){transaction.setRollbackOnly();return false;}
            return true;
        });
    }
    public boolean installPlan(UUID id,String tenant,String user,long expectedVersion,List<PlanUnit> plan) {
        List<PlanUnit> frozen=List.copyOf(plan);
        return tx.execute(status -> {
            Job job=get(id,tenant,user); validatePlan(job,frozen);
            String digest=hash(encode(frozen));
            int changed=db.update("UPDATE agent_video_notes_job SET status='RUNNING',version=version+1,plan_digest=? WHERE job_id=? AND version=? AND status='QUEUED' AND deadline_at>UTC_TIMESTAMP(6)",digest,id.toString(),expectedVersion);
            if(changed!=1) return false;
            for(var unit:frozen) db.update("INSERT INTO agent_video_notes_dependency(root_id,generation,unit_key,unit_json) VALUES(?,?,?,?)",id.toString(),job.generation(),unit.unitKey(),encode(unit));
            return true;
        });
    }
    /** Persistent intents survive a crash between enqueue and binding. No new queue is created. */
    public List<PlanUnit> plan(UUID id,String tenant,String user) {
        Job job=get(id,tenant,user);
        return db.queryForList("SELECT unit_json FROM agent_video_notes_dependency WHERE root_id=? AND generation=? AND JSON_UNQUOTE(JSON_EXTRACT(unit_json,'$.stage'))<>'SUMMARY' ORDER BY unit_key",id.toString(),job.generation())
                .stream().map(row->decode(row.get("unit_json"),PlanUnit.class)).toList();
    }
    public boolean bindWork(UUID id,String tenant,String user,int generation,String unitKey,UUID workId) {
        text(unitKey);
        get(id,tenant,user);
        // Require root-scoped unit identity and exact media/range: caller cannot bind another user's work.
        return tx.execute(status -> {
            var units=db.queryForList("SELECT d.unit_json,w.document FROM agent_video_notes_dependency d JOIN agent_content_work w ON w.job_id=? JOIN agent_video_notes_job j ON j.job_id=d.root_id WHERE d.root_id=? AND d.generation=? AND d.unit_key=? AND j.generation=d.generation AND j.status='RUNNING'",workId.toString(),id.toString(),generation,unitKey);
            if(units.size()!=1) return false;
            var unit=decode(units.get(0).get("unit_json"),PlanUnit.class);
            var work=decode(units.get(0).get("document"),com.tianmu.agent.video.content.ContentWork.Unit.class);
            Job job=get(id,tenant,user);
            if(!work.asset().equals(job.request().asset()) || !work.segmentId().equals(unit.segmentId(id))
                    || work.stage()!=unit.stage() || !work.stageVersion().equals(stageVersion(job,unit))
                    || work.startMs()!=unit.dependencyStartMs() || work.endMs()!=unit.dependencyEndMs()) throw error("NOTES_WORK_CONFLICT");
            return db.update("UPDATE agent_video_notes_dependency SET work_id=? WHERE root_id=? AND generation=? AND unit_key=? AND (work_id IS NULL OR work_id=?)",workId.toString(),id.toString(),generation,unitKey,workId.toString())==1;
        });
    }
    public Call prepareCall(UUID id,String tenant,String user,int generation,String operationKey,long reservedCost) {
        text(operationKey); if(reservedCost<=0) throw new IllegalArgumentException("Invalid reservation");
        return tx.execute(status -> {
            lock(id); Job job=get(id,tenant,user);
            Optional<Call> previous=findCall(id,generation,operationKey);
            if(previous.isPresent()) {
                if(previous.get().reservedMicros()!=reservedCost) throw error("NOTES_CALL_CONFLICT");
                return previous.get();
            }
            int changed=db.update("UPDATE agent_video_notes_job SET reserved_micros=reserved_micros+? WHERE job_id=? AND generation=? AND status='RUNNING' AND deadline_at>UTC_TIMESTAMP(6) AND ?<=budget_micros-used_micros-reserved_micros",reservedCost,id.toString(),generation,reservedCost);
            if(changed!=1) throw error("NOTES_BUDGET_OR_STATE");
            UUID call=UUID.randomUUID();
            db.update("INSERT INTO agent_video_notes_call(call_id,root_id,generation,operation_key,state,reserved_micros) VALUES(?,?,?,?,'PREPARED',?)",call.toString(),id.toString(),generation,operationKey,reservedCost);
            return findCall(id,generation,operationKey).orElseThrow();
        });
    }
    public record BoundUnit(Job job, PlanUnit unit) {}
    public Optional<Call> call(UUID id,String tenant,String user,int generation,String operationKey) {
        text(operationKey);var root=get(id,tenant,user);
        if(root.generation()!=generation)return Optional.empty();return findCall(id,generation,operationKey);
    }
    public static String qualityReceiptDigest(NotesDocument.QualityReceipt quality) {
        List<String> fields=new ArrayList<>(List.of("notes-quality-v1",quality.documentDigest(),quality.policyDigest(),Boolean.toString(quality.passed())));
        fields.addAll(quality.majorIssues());return hash(fields.toArray(String[]::new));
    }
    /** Internal coordinator lookup, not an end-user authorization API. */
    public Optional<BoundUnit> lookupBoundWork(UUID workId) {
        var rows=db.queryForList("SELECT j.job_id,j.tenant_id,j.user_id,d.unit_json FROM agent_video_notes_dependency d JOIN agent_video_notes_job j ON j.job_id=d.root_id WHERE d.work_id=? AND d.generation=j.generation AND j.status='RUNNING' AND j.deadline_at>UTC_TIMESTAMP(6)",workId.toString());
        if(rows.isEmpty()) return Optional.empty();
        if(rows.size()!=1)throw error("NOTES_WORK_CONFLICT");
        var row=rows.get(0);
        return Optional.of(new BoundUnit(get(UUID.fromString(row.get("job_id").toString()),row.get("tenant_id").toString(),row.get("user_id").toString()),decode(row.get("unit_json"),PlanUnit.class)));
    }
    /** Sole send permission: lock root/work/call, prove bound scope, then persist the dispatch fence. */
    public boolean beginWorkDispatch(UUID callId,ContentWork.Lease lease) {
        return tx.execute(status -> {
            var initial=db.queryForList("SELECT root_id FROM agent_video_notes_call WHERE call_id=?",callId.toString());
            if(initial.isEmpty())return false;
            UUID root=UUID.fromString(initial.get(0).get("root_id").toString());lock(root);
            var bound=lookupBoundWork(lease.jobId());
            if(bound.isEmpty() || !root.equals(bound.get().job().id()))return false;
            var work=activeWork(lease);
            if(work.isEmpty())return false;
            var b=bound.get();var unit=decode(work.get().get("document"),ContentWork.Unit.class);
            var grant=decode(work.get().get("grant_json"),ContentWork.Grant.class);
            if(!unit.asset().equals(b.job().request().asset()) || !grant.asset().equals(unit.asset())
                    || !grant.expiresAt().isAfter(Instant.now()) || unit.stage()!=b.unit().stage()
                    || !unit.stageVersion().equals(stageVersion(b.job(),b.unit()))
                    || !unit.segmentId().equals(b.unit().segmentId(root))
                    || unit.startMs()!=b.unit().dependencyStartMs() || unit.endMs()!=b.unit().dependencyEndMs())return false;
            if(unit.stage()==ContentWork.Stage.SUMMARY && (!sourceSnapshotDigest(lockedSources(b.job())).equals(b.unit().inputDigest())
                    || hasUnknownCalls(root,b.job().request().tenantId(),b.job().request().userId())))return false;
            String base=b.unit().segmentId(root),operation=base;
            if(unit.stage()==ContentWork.Stage.SUMMARY) {
                operation=db.queryForObject("SELECT operation_key FROM agent_video_notes_call WHERE call_id=?",String.class,callId.toString());
                if(!Set.of(base+":draft",base+":check",base+":repair",base+":recheck",base+":arbitrate").contains(operation))return false;
                if(operation.equals(base+":check") && db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND generation=? AND work_id=? AND operation_key=? AND state='COMMITTED'",Integer.class,root.toString(),b.job().generation(),lease.jobId().toString(),base+":draft")!=1)return false;
                if(operation.equals(base+":repair") && db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND generation=? AND work_id=? AND operation_key=? AND state='COMMITTED'",Integer.class,root.toString(),b.job().generation(),lease.jobId().toString(),base+":check")!=1)return false;
                if(operation.equals(base+":recheck") && db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND generation=? AND work_id=? AND operation_key=? AND state='COMMITTED'",Integer.class,root.toString(),b.job().generation(),lease.jobId().toString(),base+":repair")!=1)return false;
                if(operation.equals(base+":arbitrate") && db.queryForObject("SELECT COUNT(*) FROM agent_video_notes_call WHERE root_id=? AND generation=? AND work_id=? AND operation_key=? AND state='COMMITTED'",Integer.class,root.toString(),b.job().generation(),lease.jobId().toString(),base+":recheck")!=1)return false;
            }
            return db.update("UPDATE agent_video_notes_call SET state='DISPATCHING',work_id=?,work_fence=?,work_owner=?,dispatched_at=UTC_TIMESTAMP(6) WHERE call_id=? AND state='PREPARED' AND root_id=? AND generation=? AND operation_key=?",lease.jobId().toString(),lease.fence(),lease.worker(),callId.toString(),root.toString(),b.job().generation(),operation)==1;
        });
    }
    private Optional<Map<String,Object>> activeWork(ContentWork.Lease lease) {
        return db.queryForList("SELECT document,grant_json FROM agent_content_work WHERE job_id=? AND fence=? AND lease_owner=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6) AND deadline_at>UTC_TIMESTAMP(6) FOR UPDATE",lease.jobId().toString(),lease.fence(),lease.worker()).stream().findFirst();
    }
    /** Crash reconciliation never marks a healthy active lease UNKNOWN or refunds its reservation. */
    public int recoverExpiredDispatches(int limit) {
        if(limit<1 || limit>128)throw new IllegalArgumentException("Invalid recovery limit");
        var candidates=db.queryForList("SELECT c.call_id,c.root_id FROM agent_video_notes_call c WHERE c.state='DISPATCHING' AND c.work_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM agent_content_work w WHERE w.job_id=c.work_id AND w.fence=c.work_fence AND w.lease_owner=c.work_owner AND w.status='RUNNING' AND w.lease_until>UTC_TIMESTAMP(6) AND w.deadline_at>UTC_TIMESTAMP(6)) ORDER BY c.dispatched_at,c.call_id LIMIT ?",limit);
        int changed=0;
        for(var candidate:candidates) changed+=tx.execute(status -> {
            lock(UUID.fromString(candidate.get("root_id").toString()));
            var rows=db.queryForList("SELECT * FROM agent_video_notes_call WHERE call_id=? FOR UPDATE",candidate.get("call_id"));
            if(rows.isEmpty() || !"DISPATCHING".equals(rows.get(0).get("state")))return 0;
            var row=rows.get(0);
            if(row.get("work_id")==null)return 0;
            var lease=new ContentWork.Lease(UUID.fromString(row.get("work_id").toString()),number(row,"work_fence"),row.get("work_owner").toString());
            if(activeWork(lease).isPresent())return 0;
            return db.update("UPDATE agent_video_notes_call SET state='UNKNOWN' WHERE call_id=? AND state='DISPATCHING'",candidate.get("call_id"));
        });
        return changed;
    }
    public boolean stageWorkResult(UUID callId,ContentWork.Lease lease,String reference,String digest) {
        text(reference);digest(digest);
        return tx.execute(status -> {
            var initial=db.queryForList("SELECT root_id FROM agent_video_notes_call WHERE call_id=?",callId.toString());
            if(initial.isEmpty())return false;
            lock(UUID.fromString(initial.get(0).get("root_id").toString()));
            var bound=lookupBoundWork(lease.jobId());if(bound.isEmpty() || activeWork(lease).isEmpty())return false;
            return db.update("UPDATE agent_video_notes_call SET state='RESULT_STAGED',result_ref=?,result_digest=? WHERE call_id=? AND root_id=? AND generation=? AND state='DISPATCHING' AND work_id=? AND work_fence=? AND work_owner=?",reference,digest,callId.toString(),bound.get().job().id().toString(),bound.get().job().generation(),lease.jobId().toString(),lease.fence(),lease.worker())==1;
        });
    }
    /** Checkpoint metadata and phase receipt become visible together, never replay a staged model. */
    public boolean stageSynthesisResult(UUID callId,ContentWork.Lease lease,String reference,String digest,NotesSynthesisObjects.Reference checkpoint) {
        Objects.requireNonNull(checkpoint);
        return tx.execute(status->{
            if(!stageWorkResult(callId,lease,reference,digest))return false;
            var units=lookupBoundWork(lease.jobId());
            if(units.isEmpty() || units.get().unit().stage()!=ContentWork.Stage.SUMMARY)throw error("NOTES_WORK_CONFLICT");
            int changed=db.update("UPDATE agent_video_notes_call SET checkpoint_json=? WHERE call_id=? AND state='RESULT_STAGED' AND work_id=? AND work_fence=? AND work_owner=?",encode(checkpoint),callId.toString(),lease.jobId().toString(),lease.fence(),lease.worker());
            if(changed!=1)throw error("NOTES_CHECKPOINT_CONFLICT");return true;
        });
    }
    public Optional<NotesSynthesisObjects.Reference> synthesisCheckpoint(UUID callId) {
        var rows=db.queryForList("SELECT checkpoint_json FROM agent_video_notes_call WHERE call_id=? AND state IN ('RESULT_STAGED','COMMITTED')",callId.toString());
        if(rows.isEmpty() || rows.get(0).get("checkpoint_json")==null)return Optional.empty();
        return Optional.of(decode(rows.get(0).get("checkpoint_json"),NotesSynthesisObjects.Reference.class));
    }
    public boolean markUnknown(UUID callId) {
        return tx.execute(status -> {
            var roots=db.queryForList("SELECT root_id FROM agent_video_notes_call WHERE call_id=?",callId.toString());
            if(roots.isEmpty())return false;
            lock(UUID.fromString(roots.get(0).get("root_id").toString()));
            return db.update("UPDATE agent_video_notes_call SET state='UNKNOWN' WHERE call_id=? AND state='DISPATCHING'",callId.toString())==1;
        });
    }
    public boolean commitCall(UUID callId,long actualCost) {
        if(actualCost<0) throw new IllegalArgumentException("Invalid actual cost");
        return tx.execute(status -> {
            var initial=db.queryForList("SELECT root_id FROM agent_video_notes_call WHERE call_id=?",callId.toString());
            if(initial.isEmpty()) throw error("NOTES_CALL_NOT_FOUND");
            UUID root=UUID.fromString(initial.get(0).get("root_id").toString()); lock(root);
            var row=db.queryForMap("SELECT * FROM agent_video_notes_call WHERE call_id=? FOR UPDATE",callId.toString());
            if("COMMITTED".equals(row.get("state"))) return actualCost==number(row,"actual_micros");
            long reserved=number(row,"reserved_micros");
            if(!"RESULT_STAGED".equals(row.get("state")) || actualCost>reserved) return false;
            int changed=db.update("UPDATE agent_video_notes_job SET reserved_micros=reserved_micros-?,used_micros=used_micros+? WHERE job_id=? AND generation=? AND status='RUNNING' AND reserved_micros>=? AND deadline_at>UTC_TIMESTAMP(6)",reserved,actualCost,root.toString(),row.get("generation"),reserved);
            if(changed!=1) return false;
            db.update("UPDATE agent_video_notes_call SET state='COMMITTED',actual_micros=? WHERE call_id=?",actualCost,callId.toString()); return true;
        });
    }
    private void lock(UUID root) { if(db.queryForList("SELECT job_id FROM agent_video_notes_job WHERE job_id=? FOR UPDATE",root.toString()).isEmpty()) throw error("NOTES_NOT_FOUND"); }
    private Optional<Call> findCall(UUID id,int generation,String key) {
        var rows=db.queryForList("SELECT * FROM agent_video_notes_call WHERE root_id=? AND generation=? AND operation_key=?",id.toString(),generation,key);
        return rows.stream().findFirst().map(r->new Call(UUID.fromString(r.get("call_id").toString()),id,generation,key,CallState.valueOf(r.get("state").toString()),number(r,"reserved_micros"),(String)r.get("result_ref"),(String)r.get("result_digest")));
    }
    static String rootKey(Create r) {
        var p=r.policy();
        return hash(r.tenantId(),r.userId(),Long.toString(r.asset().videoId()),r.asset().sourceVersion(),r.asset().authorizationDomain(),Long.toString(r.asset().revocationEpoch()),Long.toString(r.durationMs()),
                p.language(),p.schemaVersion(),p.analysisVersion(),p.generationVersion(),Long.toString(p.segmentMs()),Long.toString(p.overlapMs()),Long.toString(p.budgetMicros()));
    }
    private static void validatePlan(Job job,List<PlanUnit> units) {
        if(units.size()!=2*((job.request().durationMs()+job.request().policy().segmentMs()-1)/job.request().policy().segmentMs())) throw error("NOTES_INVALID_PLAN");
        var keys=new HashSet<String>();
        for(var unit:units) if(!keys.add(unit.unitKey()) || unit.dependencyEndMs()>job.request().durationMs()
                || !List.of(com.tianmu.agent.video.content.ContentWork.Stage.TRANSCRIBE,com.tianmu.agent.video.content.ContentWork.Stage.VISION).contains(unit.stage())
                || unit.endMs()-unit.startMs()>job.request().policy().segmentMs()
                || unit.startMs()-unit.dependencyStartMs()>job.request().policy().overlapMs()
                || unit.dependencyEndMs()-unit.endMs()>job.request().policy().overlapMs()) throw error("NOTES_INVALID_PLAN");
        for(var stage:List.of(com.tianmu.agent.video.content.ContentWork.Stage.TRANSCRIBE,com.tianmu.agent.video.content.ContentWork.Stage.VISION)) {
            long cursor=0;
            for(var unit:units.stream().filter(u->u.stage()==stage).sorted(Comparator.comparingLong(PlanUnit::startMs)).toList()) {
                if(unit.startMs()!=cursor) throw error("NOTES_INVALID_PLAN"); cursor=unit.endMs();
            }
            if(cursor!=job.request().durationMs()) throw error("NOTES_INVALID_PLAN");
        }
    }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid notes data",e);} }
    private <T>T decode(Object value,Class<T> type) { try{return json.readValue(value.toString(),type);}catch(Exception e){throw new IllegalStateException("Invalid persisted notes data",e);} }
    private static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    private static String stageVersion(Job job,PlanUnit unit){return unit.stage()==ContentWork.Stage.SUMMARY?job.request().policy().generationVersion():job.request().policy().analysisVersion();}
    private static AgentException error(String code){return new AgentException(code,code);}
}
