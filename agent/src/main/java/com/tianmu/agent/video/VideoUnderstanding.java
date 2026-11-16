package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import com.tianmu.agent.video.content.TimedTranscript;
import io.opentelemetry.context.Context;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.Function;

/** Bounded independent audio/visual experiment. No unjoined result may enter the facts cache. */
@Component
@EnableConfigurationProperties(VideoUnderstandingPolicy.class)
public final class VideoUnderstanding implements AutoCloseable {
    public record Result(TimedTranscript transcript, JsonNode visual) {}
    private final VideoUnderstandingPolicy policy;
    private final ThreadPoolExecutor executor;
    private final Semaphore slots;
    public VideoUnderstanding(VideoUnderstandingPolicy policy) {
        this.policy=policy; slots=new Semaphore(policy.workers());
        executor=policy.parallel()?new ThreadPoolExecutor(policy.workers(),policy.workers(),0,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(policy.workers()), r->{Thread t=new Thread(r,"video-understanding");t.setDaemon(true);return t;}):null;
    }
    public String strategy() { return policy.parallel()?"independent-av-v1":"speech-assisted-vision-v1"; }
    public Result understand(CallControl parent, Function<CallControl,TimedTranscript> audio,
                             java.util.function.BiFunction<String,CallControl,JsonNode> visual) {
        if (!policy.parallel()) {
            TimedTranscript speech=audio.apply(parent);
            return new Result(speech, visual.apply(speech==null?"":speech.rawText(),parent));
        }
        // Reserve both workers atomically; reject overflow before either provider is called.
        if (!slots.tryAcquire(2)) throw new AgentException("VIDEO_ANALYSIS_CAPACITY_EXCEEDED","Video understanding is busy");
        var cancellation=new CancellationToken();
        var child=new CallControl(parent.deadline(),cancellation,parent.budget(),parent.externalCallStarted(),
                parent.textOutput(),parent.dispatchBarrier(),parent.frozenContext());
        Future<TimedTranscript> speech=null; Future<JsonNode> image=null;
        try(var link=parent.cancellation().onCancel(()->cancellation.cancel(parent.cancellation().reason()))) {
            speech=submit(()->audio.apply(child));
            image=submit(()->visual.apply("",child));
            while(!speech.isDone() || !image.isDone()) {
                VideoAnalysisFlights.checkpoint(parent);
                if(speech.isDone()) value(speech);
                if(image.isDone()) value(image);
                try { Thread.sleep(Math.min(20,Math.max(1,Duration.between(Instant.now(),parent.deadline()).toMillis()))); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AgentException("CANCELLED","Video understanding interrupted"); }
            }
            VideoAnalysisFlights.checkpoint(parent);
            return new Result(value(speech),value(image));
        } finally {
            cancellation.cancel(StopReason.USER_CANCELLED);
            // A submitted worker releases its own permit only when its adapter actually exits.
            if(speech==null)slots.release();
            if(image==null)slots.release();
        }
    }
    private <T> Future<T> submit(Supplier<T> work) {
        return executor.submit(Context.current().wrap((Callable<T>)()->{try{return work.get();}finally{slots.release();}}));
    }
    private static <T> T value(Future<T> result) {
        try { return result.get(); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AgentException("CANCELLED","Video understanding interrupted");}
        catch(ExecutionException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error fatal)throw fatal;throw new AgentException("VIDEO_MODEL_UNAVAILABLE","Video understanding failed");}
    }
    @jakarta.annotation.PreDestroy public void close(){if(executor!=null)executor.shutdown();}
}
