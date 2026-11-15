package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.spi.CompletionEvaluator;
import java.util.*;
import org.springframework.stereotype.Component;

/** No transcript text means a host-owned evidence-gap response, never a model guess. */
@Component
public final class TranscriptCompletionOwner implements CompletionOwner {
    public Set<String> taskTypes(){return Set.of("video.transcript");}
    public CompletionEvaluator.Decision evaluate(CompletionEvaluator.Input input) {
        if(input.task()==null || !(input.task().specification().typed() instanceof TaskConditions.Transcript frozen))return new CompletionEvaluator.Reject("INVALID_EVIDENCE");
        var results=input.exchanges().stream().filter(e->e.call().name().equals("read_video_transcript") && e.result().tool().equals("read_video_transcript") && e.result().evidenceId()!=null)
                .map(e->input.evidence().get(e.result().evidenceId())).filter(Objects::nonNull)
                .filter(e->e.runId().equals(input.task().runId()) && e.owner().equals(input.task().scope()) && e.transcript()!=null && e.transcript().context().equals(frozen.context())).toList();
        if(results.isEmpty())return input.answerProposal()==null?new CompletionEvaluator.Continue("TRANSCRIPT_REQUIRED"):new CompletionEvaluator.Reject("INVALID_EVIDENCE");
        var latest=results.get(results.size()-1);
        if(latest.transcript().fragments().isEmpty()) {
            String text=switch(latest.transcript().status()) {
                case "NO_TEXT" -> "这段音频没有可用的转写文字，目前无法据此解释内容；这不表示视频没有声音。";
                case "LIMITED" -> "本次读取达到限制，尚未取得可用转写，暂时无法解释这段内容。";
                default -> "当前播放范围还缺少可用的音频转写，我暂时无法据此解释。你可以指定更明确的片段，或稍后重试。";
            };
            return new CompletionEvaluator.Deliver(new Answer(text,List.of(latest.id())),StopReason.TOOL_DELIVERY);
        }
        if(input.answerProposal()==null)return new CompletionEvaluator.Continue("TRANSCRIPT_AVAILABLE");
        if(!input.answerProposal().evidenceIds().equals(List.of(latest.id())))return new CompletionEvaluator.Reject("INVALID_EVIDENCE");
        return new CompletionEvaluator.Deliver(input.answerProposal(),input.proposalReason());
    }
}
