package com.tianmu.agent.video.content;

import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContentWorkerSchedulerTest {
    @Test void closeCancelsWorkerWithoutStartingOverlappingWork() throws Exception {
        var worker=mock(ContentTranscriptionWorker.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var calls=new AtomicInteger();
        when(worker.runOnce(any())).thenAnswer(i->{calls.incrementAndGet();entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return ContentTranscriptionWorker.Outcome.FAILED;});
        var scheduler=new ContentWorkerScheduler(worker,()->new CallControl(Instant.now().plusSeconds(10),new CancellationToken()),event->{},Duration.ofSeconds(1));
        try {
            scheduler.start();scheduler.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
            scheduler.close();verify(worker).close();assertEquals(1,calls.get());assertFalse(scheduler.awaitTermination(Duration.ofMillis(50)));
            assertThrows(IllegalStateException.class,scheduler::start);
        } finally {release.countDown();scheduler.close();}
        assertTrue(scheduler.awaitTermination(Duration.ofSeconds(5)));
    }
    @Test void validatesPollInterval() {
        var worker=mock(ContentTranscriptionWorker.class);
        assertThrows(IllegalArgumentException.class,()->new ContentWorkerScheduler(worker,()->null,event->{},Duration.ofMillis(999)));
    }
}
