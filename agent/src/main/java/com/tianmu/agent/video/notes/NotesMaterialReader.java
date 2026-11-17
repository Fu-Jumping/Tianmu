package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.video.content.*;
import java.util.*;

/** Assemble verified source objects; summary models cannot manufacture a processing manifest. */
public final class NotesMaterialReader {
    public record Source(String coverageId,NotesJob.PlanUnit plan,ContentWork.Receipt receipt,NotesSourceProcessor.Evidence evidence) {}
    public record Prepared(UUID rootId,int generation,long version,String planDigest,NotesCoverage coverage,List<Source> sources) {
        public Prepared { sources=List.copyOf(sources); }
        public void requireMatching(NotesDocument document) {
            if(!coverage.equals(document.coverage())) throw new AgentException("NOTES_SOURCE_CONFLICT","Document coverage differs from verified source objects");
        }
    }
    private final MysqlNotesJobStore notes;
    private final MysqlContentWorkStore work;
    private final NotesSourceStore sources;
    private final NotesCoordinator.ProcessingGrants authority;
    private final int maxUnits,maxCharacters;
    public NotesMaterialReader(MysqlNotesJobStore notes,MysqlContentWorkStore work,NotesSourceStore sources,
                               NotesCoordinator.ProcessingGrants authority,int maxUnits,int maxCharacters) {
        this.notes=Objects.requireNonNull(notes); this.work=Objects.requireNonNull(work); this.sources=Objects.requireNonNull(sources); this.authority=Objects.requireNonNull(authority);
        if(maxUnits<2 || maxUnits>10000 || maxCharacters<1 || maxCharacters>10000000) throw new IllegalArgumentException("Invalid material limits");
        this.maxUnits=maxUnits; this.maxCharacters=maxCharacters;
    }
    public Prepared read(UUID id,String tenant,String user,CallControl control) {
        control.checkpoint(); var root=notes.get(id,tenant,user);
        if(root.status()!=NotesJob.Status.RUNNING || root.planDigest()==null) throw error("NOTES_NOT_READY");
        authority.requireRoot(root,control);
        if(notes.hasUnknownCalls(id,tenant,user)) throw error("NOTES_CALL_UNKNOWN");
        var dependencies=notes.dependencies(id,tenant,user);
        if(dependencies.isEmpty() || dependencies.size()>maxUnits) throw error("NOTES_MATERIAL_LIMIT");
        Map<Long,List<Source>> grouped=new TreeMap<>(); List<Source> loaded=new ArrayList<>(); long characters=0;
        for(var dependency:dependencies) {
            control.checkpoint();
            if(dependency.workId()==null) throw error("NOTES_NOT_READY");
            var job=work.get(dependency.workId()); var plan=dependency.unit(); var unit=job.unit();
            if(job.status()!=ContentWork.Status.SUCCEEDED || job.result()==null) throw error("NOTES_NOT_READY");
            if(!unit.asset().equals(root.request().asset()) || !unit.segmentId().equals(plan.segmentId(root.id()))
                    || unit.stage()!=plan.stage() || !unit.stageVersion().equals(root.request().policy().analysisVersion())
                    || unit.startMs()!=plan.dependencyStartMs() || unit.endMs()!=plan.dependencyEndMs()) throw error("NOTES_SOURCE_CONFLICT");
            var evidence=sources.read(job,job.result());
            if(!evidence.unit().equals(unit)) throw error("NOTES_SOURCE_CONFLICT");
            if(evidence.transcript()!=null) characters+=evidence.transcript().rawText().length();
            if(evidence.visual()!=null) characters+=evidence.visual().summary().length()+evidence.visual().observations().stream().mapToLong(m->m.observation().length()).sum();
            if(characters>maxCharacters) throw error("NOTES_MATERIAL_LIMIT"); // no silent truncation
            String coverageId="clip:"+plan.startMs()+":"+plan.endMs();
            var source=new Source(coverageId,plan,job.result(),evidence); loaded.add(source);
            grouped.computeIfAbsent(plan.startMs(),ignored->new ArrayList<>()).add(source);
        }
        List<NotesCoverage.Unit> units=new ArrayList<>();
        for(var pair:grouped.values()) {
            if(pair.size()!=2) throw error("NOTES_SOURCE_CONFLICT");
            var audio=pair.stream().filter(s->s.plan().stage()==ContentWork.Stage.TRANSCRIBE).findFirst().orElseThrow(()->error("NOTES_SOURCE_CONFLICT"));
            var visual=pair.stream().filter(s->s.plan().stage()==ContentWork.Stage.VISION).findFirst().orElseThrow(()->error("NOTES_SOURCE_CONFLICT"));
            var a=audio.plan(); var v=visual.plan();
            if(a.endMs()!=v.endMs() || a.dependencyStartMs()!=v.dependencyStartMs() || a.dependencyEndMs()!=v.dependencyEndMs()) throw error("NOTES_SOURCE_CONFLICT");
            units.add(new NotesCoverage.Unit(audio.coverageId(),root.request().asset(),new NotesCoverage.Range(a.startMs(),a.endMs()),
                    new NotesCoverage.Range(a.dependencyStartMs(),a.dependencyEndMs()),NotesCoverage.Audio.TRANSCRIBED,NotesCoverage.Visual.SAMPLED,
                    audio.receipt().digest(),visual.receipt().digest(),visual.evidence().visual().sampledAtMs()));
        }
        var coverage=new NotesCoverage(root.request().asset(),root.request().durationMs(),NotesJob.hash(root.request().policy().analysisVersion()),units);
        if(!coverage.fullyProcessed()) throw error("NOTES_NOT_READY");
        authority.requireRoot(root,control); control.checkpoint();
        var current=notes.get(id,tenant,user);
        if(current.version()!=root.version() || current.generation()!=root.generation() || current.status()!=NotesJob.Status.RUNNING
                || !Objects.equals(current.planDigest(),root.planDigest()) || notes.hasUnknownCalls(id,tenant,user)) throw error("NOTES_SOURCE_CONFLICT");
        return new Prepared(root.id(),root.generation(),root.version(),root.planDigest(),coverage,loaded);
    }
    private static AgentException error(String code) {return new AgentException(code,code);}
}
