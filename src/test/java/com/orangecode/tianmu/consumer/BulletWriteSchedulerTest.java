package com.orangecode.tianmu.consumer;

import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.service.BulletIngestionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulletWriteSchedulerTest {
    @Test void combinesCallbacksForHotVideoAndLetsAnotherVideoWrite() throws Exception {
        var ingestion = mock(BulletIngestionService.class);
        var entered = new LinkedBlockingQueue<List<SendBulletRequest>>();
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            List<SendBulletRequest> events = call.getArgument(0);
            entered.add(events);
            if (events.get(0).getBulletId() == 1L) assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(ingestion).persistBatch(anyList());

        try (var scheduler = new BulletWriteScheduler(ingestion)) {
            var active = scheduler.submit(events(1001, 1, 2));
            assertEquals(List.of(1L), ids(entered.poll(2, TimeUnit.SECONDS)));
            var pendingA = scheduler.submit(events(1001, 2, 22));
            var pendingB = scheduler.submit(events(1001, 22, 52));
            var other = scheduler.submit(events(1002, 52, 53));
            assertEquals(List.of(52L), ids(entered.poll(2, TimeUnit.SECONDS)));
            assertTrue(other.get(0).get(2, TimeUnit.SECONDS));
            assertFalse(active.get(0).isDone());
            assertTrue(pendingA.stream().noneMatch(java.util.concurrent.CompletableFuture::isDone));
            assertTrue(entered.isEmpty());
            release.countDown();
            assertEquals(LongStream.range(2, 52).boxed().toList(), ids(entered.poll(2, TimeUnit.SECONDS)));
            for (var group : List.of(active, pendingA, pendingB))
                for (var future : group) assertTrue(future.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test void rejectsWholeSubmissionWhenVideoIsFullAndReturnsUnwrittenItemsOnClose() throws Exception {
        var ingestion = mock(BulletIngestionService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(ingestion).persistBatch(anyList());
        try (var scheduler = new BulletWriteScheduler(ingestion)) {
            scheduler.submit(events(1001, 1, 51));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var pending = scheduler.submit(events(1001, 51, 201));
            assertThrows(RejectedExecutionException.class, () -> scheduler.submit(List.of(
                    events(1002, 201, 202).get(0), events(1001, 202, 203).get(0))));
            scheduler.close();
            for (var result : pending) assertFalse(result.get(2, TimeUnit.SECONDS));
            verify(ingestion, times(1)).persistBatch(anyList());
        } finally {
            release.countDown();
        }
    }

    private static List<Long> ids(List<SendBulletRequest> events) {
        assertNotNull(events, "Writer did not receive a batch");
        return events.stream().map(SendBulletRequest::getBulletId).toList();
    }

    private static List<SendBulletRequest> events(long video, long first, long end) {
        return LongStream.range(first, end).mapToObj(id -> {
            var event = new SendBulletRequest();
            event.setVideoId(video);
            event.setBulletId(id);
            return event;
        }).toList();
    }
}
