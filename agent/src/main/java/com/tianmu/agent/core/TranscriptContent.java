package com.tianmu.agent.core;

import java.time.Instant;
import java.util.*;

/** Frozen playback authority and audio-only evidence; never a sampled-frame observation. */
public final class TranscriptContent {
    private TranscriptContent() {}
    public record Context(String tenantId,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) long videoId,
            String sourceVersion,String domain,long epoch,long durationMs,String stageVersion,String mode,long startMs,long endMs) {
        public Context {
            if(tenantId==null || tenantId.isBlank() || tenantId.length()>128 || videoId<=0 || sourceVersion==null || sourceVersion.isBlank() || sourceVersion.length()>128
                    || !"public".equals(domain) || epoch<0 || durationMs<=0 || durationMs>3_600_000 || stageVersion==null || !stageVersion.matches("[A-Za-z0-9_.:-]{1,128}")
                    || !Set.of("CURRENT","INTERVAL").contains(mode) || startMs<0 || endMs<startMs || endMs>durationMs
                    || "CURRENT".equals(mode) && startMs!=0 || "INTERVAL".equals(mode) && (endMs<=startMs || endMs-startMs>300000))
                throw new IllegalArgumentException("Invalid frozen transcript context");
        }
    }
    public record Range(long startMs,long endMs) { public Range {if(startMs<0 || endMs<startMs)throw new IllegalArgumentException("Invalid transcript range");} }
    public record Fragment(String citationId,String text,long startMs,long endMs,String precision) {
        public Fragment {
            if(citationId==null || !citationId.matches("ts:[a-f0-9-]{36}:[0-9]{1,4}:[a-f0-9]{64}") || text==null || text.isBlank() || text.length()>12000
                    || startMs<0 || endMs<=startMs || !Set.of("SEGMENT","CLIP").contains(precision))throw new IllegalArgumentException("Invalid transcript fragment");
        }
    }
    public record Facts(String kind,Context context,String status,Range requested,List<Fragment> fragments,List<Range> processedRanges,
            List<Range> missingRanges,List<String> limitations,Instant readAt) {
        public Facts(Context context,String status,Range requested,List<Fragment> fragments,List<Range> processedRanges,List<Range> missingRanges,List<String> limitations,Instant readAt){
            this("audio-transcript-v1",context,status,requested,fragments,processedRanges,missingRanges,limitations,readAt);
        }
        public Facts {
            if(!"audio-transcript-v1".equals(kind))throw new IllegalArgumentException("Invalid transcript evidence version");
            Objects.requireNonNull(context);Objects.requireNonNull(requested);Objects.requireNonNull(readAt);
            fragments=List.copyOf(fragments);processedRanges=List.copyOf(processedRanges);missingRanges=List.copyOf(missingRanges);limitations=List.copyOf(limitations);
            if(!Set.of("READY","PARTIAL","NOT_READY","NO_TEXT","LIMITED").contains(status) || fragments.size()>32
                    || processedRanges.size()>256 || missingRanges.size()>257 || limitations.size()>16 || limitations.stream().anyMatch(v->!v.matches("[A-Z_]{1,64}"))
                    || fragments.stream().mapToInt(f->f.text().length()).sum()>12000 || requested.startMs()<context.startMs() || requested.endMs()>context.endMs()
                    || fragments.stream().anyMatch(f->f.startMs()<context.startMs() || f.endMs()>context.endMs())
                    || fragments.stream().map(Fragment::citationId).distinct().count()!=fragments.size())throw new IllegalArgumentException("Invalid transcript evidence");
        }
    }
}
