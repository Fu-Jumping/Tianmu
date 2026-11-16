package com.tianmu.agent.video;
import com.tianmu.agent.core.VideoContent;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
/** Bounded process-local cache references; never stores grants or replaces source authorization. */
@Component
public final class RecentVideoMaterials {
    private final com.github.benmanes.caffeine.cache.Cache<String,List<Entry>> recent;
    record Entry(String key,long start,long end){}
    public RecentVideoMaterials(){recent=com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(30)).build();}
    public void remember(String namespace,String key,VideoContent.Facts facts){
        recent.asMap().compute(namespace,(n,old)->{
            var entries=new ArrayList<Entry>();entries.add(new Entry(key,facts.startMs(),facts.endMs()));
            if(old!=null)old.stream().filter(e->!e.key().equals(key)).limit(7).forEach(entries::add);
            return List.copyOf(entries);
        });
    }
    public Optional<VideoContent.Facts> find(String namespace,long from,long to,long allowedStart,
                                          VideoContent.Source source,VideoContentCache cache){
        var entries=recent.getIfPresent(namespace);if(entries==null)return Optional.empty();
        return entries.stream().filter(e->e.start()>=allowedStart && e.start()<=from && e.end()<=to && e.end()-from>=15000)
                .sorted(Comparator.comparingLong(Entry::end).reversed()).map(e->cache.get(e.key())).filter(Objects::nonNull)
                .filter(f->f.videoId()==source.videoId() && f.sourceVersion().equals(source.sourceVersion())
                        && f.startMs()>=allowedStart && f.startMs()<=from && f.endMs()<=to && f.endMs()-from>=15000)
                .findFirst();
    }
}
