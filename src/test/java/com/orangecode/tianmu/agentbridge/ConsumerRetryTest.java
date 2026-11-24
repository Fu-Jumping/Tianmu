package com.orangecode.tianmu.agentbridge;

import com.orangecode.tianmu.consumer.BulletWriteScheduler;
import com.orangecode.tianmu.consumer.RocketMQConsumer;
import com.orangecode.tianmu.service.BulletIngestionService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
import static org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.RECONSUME_LATER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsumerRetryTest {
    private static final ConsumeConcurrentlyContext CONTEXT =
            new ConsumeConcurrentlyContext(new MessageQueue("tianmu-topic", "test-broker", 0));

    @Test void nativeBatchListenerWaitsForCommitAcrossVideos() throws Exception {
        var storage = mock(BulletIngestionService.class);
        var client = mock(DefaultMQPushConsumer.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(storage).persistBatch(argThat(events -> events.get(0).getVideoId() == 1001L));

        var callback = Executors.newSingleThreadExecutor();
        try (var scheduler = new BulletWriteScheduler(storage)) {
            var listener = listener(scheduler, client);
            var result = callback.submit(() -> listener.consumeMessage(List.of(event(9001, 1001), event(9002, 1002)), CONTEXT));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            verify(storage, timeout(2000)).persistBatch(argThat(events -> events.get(0).getVideoId() == 1002L));
            assertFalse(result.isDone());
            release.countDown();
            assertEquals(CONSUME_SUCCESS, result.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            callback.shutdownNow();
        }
    }

    @Test void handsBackMalformedAndIsolatedPoisonMessages() throws Exception {
        var storage = mock(BulletIngestionService.class);
        var client = mock(DefaultMQPushConsumer.class);
        var valid = event(9001, 1001);
        var invalid = event(9002, 1001);
        var malformed = message("broken json");
        doThrow(new IllegalArgumentException("conflicting event")).when(storage).persistBatch(anyList());
        doThrow(new IllegalArgumentException("conflicting event")).when(storage).persist(argThat(event -> event.getBulletId() == 9002L));
        try (var scheduler = new BulletWriteScheduler(storage)) {
            var listener = listener(scheduler, client);
            assertEquals(CONSUME_SUCCESS, listener.consumeMessage(List.of(valid, invalid, malformed), CONTEXT));
            verify(storage).persist(argThat(event -> event.getBulletId() == 9001L));
            verify(client).sendMessageBack(invalid, 0, "test-broker");
            verify(client).sendMessageBack(malformed, 0, "test-broker");
            verify(client, never()).sendMessageBack(eq(valid), anyInt(), anyString());
            doThrow(new IllegalStateException("broker unavailable")).when(client).sendMessageBack(malformed, 0, "test-broker");
            assertEquals(RECONSUME_LATER, listener.consumeMessage(List.of(malformed), CONTEXT));
        }
    }

    @Test void storageFailureGoesBackToBrokerWithoutRepeatingSingleWrites() throws Exception {
        var storage = mock(BulletIngestionService.class);
        var client = mock(DefaultMQPushConsumer.class);
        var first = event(9001, 1001);
        var second = event(9002, 1001);
        doThrow(new IllegalStateException("database unavailable")).when(storage).persistBatch(anyList());
        try (var scheduler = new BulletWriteScheduler(storage)) {
            assertEquals(CONSUME_SUCCESS, listener(scheduler, client).consumeMessage(List.of(first, second), CONTEXT));
            verify(storage, never()).persist(any());
            verify(client).sendMessageBack(first, 0, "test-broker");
            verify(client).sendMessageBack(second, 0, "test-broker");
        }
    }

    @Test void storageFailureEscapesConsumerSoBrokerCanRetry() {
        var storage = mock(BulletIngestionService.class);
        doThrow(new IllegalStateException("database unavailable")).when(storage).persistBatch(anyList());
        try (var scheduler = new BulletWriteScheduler(storage)) {
            var consumer = new RocketMQConsumer();
            ReflectionTestUtils.setField(consumer, "writes", scheduler);
            assertThrows(IllegalStateException.class, () -> consumer.onMessage("{\"bulletId\":9001,\"videoId\":1001}"));
            verify(storage).persistBatch(anyList());
        }
    }

    private static MessageListenerConcurrently listener(BulletWriteScheduler scheduler, DefaultMQPushConsumer client) {
        var consumer = new RocketMQConsumer();
        ReflectionTestUtils.setField(consumer, "writes", scheduler);
        consumer.prepareStart(client);
        var listener = ArgumentCaptor.forClass(MessageListenerConcurrently.class);
        verify(client).registerMessageListener(listener.capture());
        return listener.getValue();
    }

    private static MessageExt event(long id, long video) {
        return message("{\"bulletId\":" + id + ",\"videoId\":" + video + "}");
    }

    private static MessageExt message(String body) {
        var message = new MessageExt();
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
