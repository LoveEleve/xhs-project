package com.myxhs.test;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class SendCompensation {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: SendCompensation <action> <orderId> <userId> [failReason]");
            return;
        }
        String action = args[0];
        String orderId = args[1];
        String userId = args[2];
        String failReason = args.length > 3 ? args[3] : "test-compensation";

        DefaultMQProducer producer = new DefaultMQProducer("test-compensation-producer");
        producer.setNamesrvAddr("21.130.247.89:9876");
        producer.start();
        try {
            String payload = String.format(
                "{\"action\":\"%s\",\"orderId\":%s,\"failReason\":\"%s\",\"timestamp\":%d}",
                action, orderId, failReason, System.currentTimeMillis());
            Message msg = new Message("ORDER_COMPENSATION_TOPIC", action,
                    UUID.randomUUID().toString().replace("-", ""),
                    payload.getBytes(StandardCharsets.UTF_8));
            msg.putUserProperty("userId", userId);
            org.apache.rocketmq.client.producer.SendResult result = producer.send(msg, 3000);
            System.out.println("SENT " + action + " orderId=" + orderId + " status=" + result.getSendStatus() + " msgId=" + result.getMsgId());
        } finally {
            producer.shutdown();
        }
    }
}
