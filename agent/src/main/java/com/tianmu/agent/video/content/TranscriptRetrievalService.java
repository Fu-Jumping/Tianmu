package com.tianmu.agent.video.content;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Read only published, verified assets. This never issues a platform grant or calls a model. */
public final class TranscriptRetrievalService {
    public enum Mode { CURRENT,INTERVAL }
    public enum Status { READY,PARTIAL,NOT_READY,NO_TEXT,LIMITED }
    public record Request(String sourceVersion,Mode mode,Long anchorMs,Long startMs,Long endMs,String query) {
        public Request {
            if(sourceVersion==null || sourceVersion.isBlank() || sourceVersion.length()>128 || mode==null)
                throw new IllegalArgumentException("Missing transcript source or mode");
            query=query==null?"":query.strip();
            if(query.length()>1000 || mode==Mode.CURRENT && (anchorMs==null || anchorMs<0 || startMs!=null || endMs!=null)
                    || mode==Mode.INTERVAL && (anchorMs!=null || startMs==null || endMs==null || startMs<0 || endMs<=startMs || endMs-startMs>300000))
                throw new IllegalArgumentException("Invalid transcript request");
        }
    }
    public record Range(long startMs,long endMs) {}
    public record Excerpt(String citationId,String text,long startMs,long endMs,TimedTranscript.Precision precision) {}
    public record Bundle(ContentReadAuthority.Identity identity,Mode mode,Range requested,Status status,
                         List<Excerpt> excerpts,List<Range> processedRanges,List<Range> missingRanges,List<String> limitations) {
        public Bundle { excerpts=List.copyOf(excerpts);processedRanges=List.copyOf(processedRanges);missingRanges=List.copyOf(missingRanges);limitations=List.copyOf(limitations); }
    }
    private record Scope(ContentReadAuthority.Identity identity,ContentBoundary.Window allowed,Range search) {}
    private final MysqlContentWorkStore store;
    private final ContentTranscriptStore files;
    private final ContentReadAuthority authority;
    private final TranscriptQueryProperties policy;
    private final Semaphore permits;
    public TranscriptRetrievalService(MysqlContentWorkStore store,ContentTranscriptStore files,ContentReadAuthority authority,TranscriptQueryProperties policy) {
        this.store=Objects.requireNonNull(store);this.files=Objects.requireNonNull(files);this.authority=Objects.requireNonNull(authority);
        this.policy=Objects.requireNonNull(policy);this.permits=new Semaphore(policy.concurrentQueries());
    }
    public ContentReadAuthority.Identity identity(long video,AccessScope viewer,CallControl control) {
        return bounded(()->authorized(video,viewer,control));
    }
    public Bundle query(long video,AccessScope viewer,Request request,CallControl control) {
        return bounded(()->{
            Scope scope=scope(video,viewer,request,control);
            var flags=new LinkedHashSet<String>();var candidates=new ArrayList<Excerpt>();var processed=new ArrayList<Range>();
            var jobs=store.published(scope.identity().asset(),policy.stageVersion(),scope.search().startMs(),scope.search().endMs(),policy.candidateLimit()+1);
            checkpoint(control);
            if(jobs.size()>policy.candidateLimit()){flags.add("CANDIDATE_LIMIT");jobs=jobs.subList(0,policy.candidateLimit());}
            int remaining=policy.readBytes();
            for(var job:jobs) {
                checkpoint(control);
                if(!eligible(job,scope)){flags.add("DEPENDENCY_OUTSIDE_WINDOW");continue;}
                int allowance=Math.min(2_000_000,remaining);
                if(allowance<=0){flags.add("READ_LIMIT");break;}
                try {
                    var loaded=files.read(job,job.result(),allowance);remaining-=loaded.bytes();
                    var transcript=loaded.transcript();
                    processed.add(new Range(Math.max(scope.search().startMs(),job.unit().startMs()),Math.min(scope.search().endMs(),job.unit().endMs())));
                    if(transcript.state()==TimedTranscript.State.NO_TRANSCRIPT)flags.add("NO_TRANSCRIPT");
                    for(int index=0;index<transcript.segments().size();index++) {
                        var segment=transcript.segments().get(index);
                        if(segment.endMs()<=scope.search().startMs() || segment.startMs()>=scope.search().endMs())continue;
                        if(!excerptAllowed(job,index,segment,scope))continue;
                        candidates.add(new Excerpt(citation(job,index),segment.text(),segment.startMs(),segment.endMs(),transcript.precision()));
                    }
                }catch(AgentException failure) {
                    remaining-=allowance;
                    if("CONTENT_READ_LIMIT".equals(failure.code())){flags.add("READ_LIMIT");break;}
                    flags.add("RESULT_UNAVAILABLE");
                }
            }
            candidates.sort(Comparator.<Excerpt>comparingInt(e->score(e.text(),request.query())).reversed()
                    .thenComparing(Comparator.comparingLong(Excerpt::endMs).reversed()).thenComparing(Excerpt::citationId));
            var chosen=new ArrayList<Excerpt>();var seen=new HashSet<String>();int text=0;
            for(var excerpt:candidates) {
                String duplicate=excerpt.startMs()+":"+excerpt.endMs()+":"+excerpt.text();
                if(!seen.add(duplicate))continue;
                if(chosen.size()>=policy.excerptLimit() || text+excerpt.text().length()>policy.textLimit()){flags.add("TEXT_LIMIT");continue;}
                chosen.add(excerpt);text+=excerpt.text().length();
            }
            chosen.sort(Comparator.comparingLong(Excerpt::startMs).thenComparing(Excerpt::citationId));
            var ranges=merge(processed);var gaps=missing(scope.search(),ranges);
            boolean limited=flags.contains("READ_LIMIT") || flags.contains("TEXT_LIMIT") || flags.contains("CANDIDATE_LIMIT");
            Status status=chosen.isEmpty()?(limited?Status.LIMITED:(!gaps.isEmpty() || ranges.isEmpty()?Status.NOT_READY:Status.NO_TEXT))
                    :gaps.isEmpty() && flags.isEmpty()?Status.READY:Status.PARTIAL;
            recheck(scope,video,viewer,control);
            return new Bundle(scope.identity(),request.mode(),scope.search(),status,chosen,ranges,gaps,new ArrayList<>(flags));
        });
    }
    public Excerpt citation(long video,AccessScope viewer,Request request,String id,CallControl control) {
        return bounded(()->{
            Scope scope=scope(video,viewer,request,control);
            if(id==null || !id.matches("ts:[a-f0-9-]{36}:[0-9]{1,4}:[a-f0-9]{64}"))throw notFound();
            String[] parts=id.split(":");ContentWork.Job job;
            try{job=store.get(UUID.fromString(parts[1]));}catch(IllegalArgumentException|AgentException missing){throw notFound();}
            if(!eligible(job,scope) || !job.unit().stageVersion().equals(policy.stageVersion()) || job.unit().stage()!=ContentWork.Stage.TRANSCRIBE)throw notFound();
            var transcript=files.read(job,job.result(),Math.min(2_000_000,policy.readBytes())).transcript();
            int index=Integer.parseInt(parts[2]);
            if(index>=transcript.segments().size() || !id.equals(citation(job,index)))throw notFound();
            var segment=transcript.segments().get(index);
            if(!excerptAllowed(job,index,segment,scope) || segment.endMs()<=scope.search().startMs() || segment.startMs()>=scope.search().endMs())throw notFound();
            recheck(scope,video,viewer,control);
            return new Excerpt(id,segment.text(),segment.startMs(),segment.endMs(),transcript.precision());
        });
    }
    private Scope scope(long video,AccessScope viewer,Request request,CallControl control) {
        var identity=authorized(video,viewer,control);
        if(!identity.asset().sourceVersion().equals(request.sourceVersion()))throw new AgentException("VIDEO_VERSION_CHANGED","Video has changed; refresh its context");
        long from,to;ContentBoundary.Policy boundary;
        if(request.mode()==Mode.CURRENT){to=request.anchorMs();from=Math.max(0,to-policy.lookbackMillis());boundary=ContentBoundary.Policy.WATCHED_ONLY;}
        else{from=request.startMs();to=request.endMs();boundary=ContentBoundary.Policy.EXPLICIT_RANGE;}
        if(to>identity.durationMs())throw new AgentException("INVALID_REQUEST","Playback range exceeds this video");
        return new Scope(identity,new ContentBoundary.Window(identity.asset(),boundary,boundary==ContentBoundary.Policy.WATCHED_ONLY?0:from,to,identity.durationMs()),new Range(from,to));
    }
    private ContentReadAuthority.Identity authorized(long video,AccessScope viewer,CallControl control) {
        checkpoint(control);
        if(video<=0 || !viewer.readableVideoIds().contains(video))throw new AgentException("FORBIDDEN","Video outside viewer scope");
        var identity=authority.current(video,viewer,control);checkpoint(control);
        if(identity==null || identity.asset().videoId()!=video || !identity.asset().tenantId().equals(viewer.tenantId()) || !"public".equals(identity.asset().authorizationDomain()))
            throw new AgentException("FORBIDDEN","No public content read authority");
        return identity;
    }
    private void recheck(Scope scope,long video,AccessScope viewer,CallControl control) {
        if(!scope.identity().equals(authorized(video,viewer,control)))throw new AgentException("VIDEO_VERSION_CHANGED","Video source authority changed during the query");
    }
    private boolean eligible(ContentWork.Job job,Scope scope) {
        if(job.status()!=ContentWork.Status.SUCCEEDED || job.result()==null || !job.unit().asset().equals(scope.identity().asset()))return false;
        var clip=clip(job);
        return ContentBoundary.check(clip.id(),scope.allowed(),List.of(clip)).allowed();
    }
    private static ContentBoundary.Material clip(ContentWork.Job job) {
        return new ContentBoundary.Material("clip:"+job.id(),job.unit().asset(),ContentBoundary.Kind.TRANSCRIPT,job.unit().startMs(),job.unit().endMs(),List.of());
    }
    private static boolean excerptAllowed(ContentWork.Job job,int index,TimedTranscript.Segment segment,Scope scope) {
        var clip=clip(job);
        var excerpt=new ContentBoundary.Material("segment:"+job.id()+":"+index,job.unit().asset(),ContentBoundary.Kind.DERIVED,segment.startMs(),segment.endMs(),List.of(clip.id()));
        return ContentBoundary.check(excerpt.id(),scope.allowed(),List.of(clip,excerpt)).allowed();
    }
    private static String citation(ContentWork.Job job,int index){return "ts:"+job.id()+":"+index+":"+job.result().digest();}
    private static AgentException notFound(){return new AgentException("CONTENT_CITATION_NOT_FOUND","Citation is unavailable in this video context");}
    private <T>T bounded(Supplier<T> operation) {
        if(!permits.tryAcquire())throw new AgentException("CONTENT_QUERY_BUSY","Content reader is busy; retry shortly");
        try{return operation.get();}finally{permits.release();}
    }
    private static void checkpoint(CallControl control){control.checkpoint();if(!Instant.now().isBefore(control.deadline()))throw new AgentException("CONTENT_QUERY_TIMEOUT","Content query deadline exceeded");}
    private static int score(String text,String query) {
        String target=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String normalized=java.text.Normalizer.normalize(query,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var terms=new LinkedHashSet<String>();
        for(String term:normalized.split("[^\\p{L}\\p{N}_.+-]+"))if(term.length()>1)terms.add(term);
        for(int i=0;i+1<normalized.length() && terms.size()<64;i++)
            if(Character.UnicodeScript.of(normalized.charAt(i))==Character.UnicodeScript.HAN && Character.UnicodeScript.of(normalized.charAt(i+1))==Character.UnicodeScript.HAN)
                terms.add(normalized.substring(i,i+2));
        return (int)terms.stream().limit(64).filter(target::contains).count();
    }
    private static List<Range> merge(List<Range> ranges) {
        var sorted=ranges.stream().filter(r->r.endMs()>r.startMs()).sorted(Comparator.comparingLong(Range::startMs)).toList();
        var result=new ArrayList<Range>();
        for(var range:sorted) {
            if(result.isEmpty() || result.get(result.size()-1).endMs()<range.startMs())result.add(range);
            else{var last=result.remove(result.size()-1);result.add(new Range(last.startMs(),Math.max(last.endMs(),range.endMs())));}
        }
        return List.copyOf(result);
    }
    private static List<Range> missing(Range requested,List<Range> processed) {
        var gaps=new ArrayList<Range>();long cursor=requested.startMs();
        for(var range:processed){if(range.startMs()>cursor)gaps.add(new Range(cursor,range.startMs()));cursor=Math.max(cursor,range.endMs());}
        if(cursor<requested.endMs())gaps.add(new Range(cursor,requested.endMs()));return List.copyOf(gaps);
    }
}
