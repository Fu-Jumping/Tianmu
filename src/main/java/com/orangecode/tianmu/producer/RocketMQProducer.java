package com.orangecode.tianmu.producer;

import jakarta.annotation.Resource;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RocketMQ 生产者
 */
@Component
public class RocketMQProducer {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    public CompletableFuture<Void> sendMessageAsync(String topic, String message) {
        var outcome = new CompletableFuture<Void>();
        try {
            rocketMQTemplate.asyncSend(topic, message, new SendCallback() {
                @Override public void onSuccess(SendResult result) {
                    if (result != null && result.getSendStatus() == SendStatus.SEND_OK) outcome.complete(null);
                    else outcome.completeExceptionally(new IllegalStateException("Broker did not confirm send"));
                }

                @Override public void onException(Throwable failure) {
                    outcome.completeExceptionally(failure);
                }
            }, 3000);
        } catch (RuntimeException failure) {
            outcome.completeExceptionally(failure);
        }
        // Bound the lifetime of admission permits even if the client loses a callback.
        return outcome.orTimeout(4, TimeUnit.SECONDS);
    }
}
