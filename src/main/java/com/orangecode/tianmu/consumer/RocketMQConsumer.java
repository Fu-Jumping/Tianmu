package com.orangecode.tianmu.consumer;

import com.alibaba.fastjson.JSON;
import com.orangecode.tianmu.common.DanmakuMetrics;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.service.BulletIngestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
import static org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.RECONSUME_LATER;

@Component
@Slf4j
@RocketMQMessageListener(topic = "tianmu-topic", consumerGroup = "tianmuorange-consumer-group")
public class RocketMQConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {

    @Resource
    private BulletWriteScheduler writes;

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeMessageBatchMaxSize(BulletIngestionService.MAX_BATCH_SIZE);
        consumer.setPullBatchSize(BulletIngestionService.MAX_BATCH_SIZE);
        consumer.setConsumeThreadMin(4);
        consumer.setConsumeThreadMax(4);
        consumer.setPullInterval(200);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> consume(consumer, messages, context));
    }

    private ConsumeConcurrentlyStatus consume(DefaultMQPushConsumer consumer, List<MessageExt> messages,
                                               ConsumeConcurrentlyContext context) {
        var events = new ArrayList<SendBulletRequest>(messages.size());
        var validMessages = new ArrayList<MessageExt>(messages.size());
        var failed = new ArrayList<MessageExt>();
        for (var message : messages) {
            SendBulletRequest event = decode(message);
            if (event == null) {
                failed.add(message);
            } else {
                events.add(event);
                validMessages.add(message);
            }
        }

        try {
            if (!events.isEmpty()) {
                var committed = writes.submit(events);
                CompletableFuture.allOf(committed.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
                for (int i = 0; i < committed.size(); i++) {
                    if (!committed.get(i).join()) failed.add(validMessages.get(i));
                }
            }
            // Only acknowledge after successful transactions and retry handoff for every failed item.
            for (var message : failed) {
                consumer.sendMessageBack(message, context.getDelayLevelWhenNextConsume(), context.getMessageQueue().getBrokerName());
            }
            return CONSUME_SUCCESS;
        } catch (RejectedExecutionException full) {
            DanmakuMetrics.dbRejected.increment();
            return RECONSUME_LATER;
        } catch (TimeoutException timedOut) {
            DanmakuMetrics.dbAckWaitTimeout.increment();
            return RECONSUME_LATER;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return RECONSUME_LATER;
        } catch (ExecutionException failedWrite) {
            log.warn("Danmaku write result unavailable");
            return RECONSUME_LATER;
        } catch (Exception retryFailure) {
            log.warn("Danmaku retry handoff failed; retaining original batch cause={}", retryFailure.getClass().getSimpleName());
            return RECONSUME_LATER;
        }
    }

    private SendBulletRequest decode(MessageExt message) {
        try {
            var event = JSON.parseObject(new String(message.getBody(), StandardCharsets.UTF_8), SendBulletRequest.class);
            return event != null && event.getVideoId() != null && event.getVideoId() > 0 ? event : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Override
    public void onMessage(String message) {
        SendBulletRequest event = JSON.parseObject(message, SendBulletRequest.class);
        if (event == null || event.getVideoId() == null || event.getVideoId() <= 0)
            throw new IllegalArgumentException("Invalid danmaku event");
        try {
            if (!writes.submit(List.of(event)).get(0).get(30, TimeUnit.SECONDS))
                throw new IllegalStateException("Danmaku write failed");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Danmaku write interrupted", stopped);
        } catch (ExecutionException | TimeoutException unavailable) {
            throw new IllegalStateException("Danmaku write result unavailable", unavailable);
        }
    }
}
