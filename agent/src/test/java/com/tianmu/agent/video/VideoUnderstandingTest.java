package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.TimedTranscript;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class VideoUnderstandingTest {
    private final ObjectMapper json=new ObjectMapper();
    private TimedTranscript speech(){return new TimedTranscript(0,1000,"口述",TimedTranscript.State.AVAILABLE,TimedTranscript.Precision.CLIP,List.of(new TimedTranscript.Segment(0,1000,"口述")));}
    private CallControl control(){return new CallControl(Instant.now().plusSeconds(10),new CancellationToken());}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @Test void serialDefaultStillFeedsSpeechIntoVision(){
        try(var pipeline=new VideoUnderstanding(VideoUnderstandingPolicy.defaults())){
            var result=pipeline.understand(control(),c->speech(),(text,c)->{assertEquals("口述",text);return json.createObjectNode();});
            assertEquals("口述",result.transcript().rawText());
        }
    }
    @Test void independentBranchesStartTogetherAndJoinBoth(){
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var caller=Executors.newSingleThreadExecutor();
        try(var pipeline=new VideoUnderstanding(new VideoUnderstandingPolicy(true,2))){
            var task=caller.submit(()->pipeline.understand(control(),c->{entered.countDown();await(release);return speech();},(text,c)->{assertEquals("",text);entered.countDown();await(release);return json.createObjectNode().put("seen",true);}));
            await(entered);assertFalse(task.isDone());release.countDown();
            var result=task.get(5,TimeUnit.SECONDS);assertTrue(result.visual().get("seen").booleanValue());assertEquals("口述",result.transcript().rawText());
        }catch(Exception e){throw new AssertionError(e);}finally{release.countDown();caller.shutdownNow();}
    }
    @Test void cancellationDoesNotReleaseCapacityWhileAdaptersStillExecute() throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var caller=Executors.newSingleThreadExecutor();
        try(var pipeline=new VideoUnderstanding(new VideoUnderstandingPolicy(true,2))){
            var parent=control();
            var task=caller.submit(()->pipeline.understand(parent,c->{entered.countDown();await(release);return speech();},(s,c)->{entered.countDown();await(release);return json.createObjectNode();}));
            await(entered);parent.cancellation().cancel(StopReason.USER_CANCELLED);
            assertThrows(ExecutionException.class,()->task.get(2,TimeUnit.SECONDS));
            var busy=assertThrows(AgentException.class,()->pipeline.understand(control(),c->{fail("Must not dispatch");return null;},(s,c)->{fail("Must not dispatch");return null;}));
            assertEquals("VIDEO_ANALYSIS_CAPACITY_EXCEEDED",busy.code());
        }finally{release.countDown();caller.shutdownNow();}
    }
    @Test void failedBranchCannotProduceAnAnswer() {
        try(var pipeline=new VideoUnderstanding(new VideoUnderstandingPolicy(true,2))){
            var e=assertThrows(AgentException.class,()->pipeline.understand(control(),c->{throw new AgentException("VIDEO_TRANSCRIPTION_FAILED","failed");},(s,c)->json.createObjectNode()));
            assertEquals("VIDEO_TRANSCRIPTION_FAILED",e.code());
        }
    }
    @Test void workersKeepTraceContextAndRequestControl() {
        var key=io.opentelemetry.context.ContextKey.<String>named("video-test-run");var parent=control();
        try(var pipeline=new VideoUnderstanding(new VideoUnderstandingPolicy(true,2));
            var ignored=io.opentelemetry.context.Context.current().with(key,"run-1").makeCurrent()){
            pipeline.understand(parent,c->{assertEquals("run-1",io.opentelemetry.context.Context.current().get(key));assertSame(parent.budget(),c.budget());assertEquals(parent.deadline(),c.deadline());return speech();},
                    (s,c)->{assertEquals("run-1",io.opentelemetry.context.Context.current().get(key));assertSame(parent.dispatchBarrier(),c.dispatchBarrier());return json.createObjectNode();});
        }
    }
}
