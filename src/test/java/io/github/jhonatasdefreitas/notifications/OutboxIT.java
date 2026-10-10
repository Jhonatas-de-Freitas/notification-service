package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.jhonatasdefreitas.notifications.messaging.RabbitTopology;

class OutboxIT extends IntegrationTest {

    @Autowired
    AmqpAdmin admin;

    @Test
    void aMessageWithNoQueueToGoToStaysInTheOutboxUntilTheQueueExists() {
        String queue = "notifications.test." + UUID.randomUUID();
        String notificationId = UUID.randomUUID().toString();

        try {
            jdbc.sql("insert into outbox (notification_id, queue, attempt, created_at) values (?::uuid, ?, 1, now())")
                    .params(notificationId, queue).update();

            // The broker confirms this message every time, and every time hands it back.
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))
                    .until(() -> inOutbox(notificationId) == 1);

            // Durable because RabbitMQ 4 refuses a transient queue that is not exclusive.
            admin.declareQueue(new Queue(queue));
            admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, RabbitTopology.EXCHANGE, queue, null));

            awaitPublished(notificationId);
            Message published = rabbit.receive(queue, PATIENCE.toMillis());
            assertThat(published).isNotNull();
            assertThat(new String(published.getBody(), StandardCharsets.UTF_8)).isEqualTo(notificationId);
        } finally {
            jdbc.sql("delete from outbox where queue = ?").param(queue).update();
            admin.deleteQueue(queue);
        }
    }

    private int inOutbox(String notificationId) {
        return jdbc.sql("select count(*) from outbox where notification_id = ?::uuid")
                .param(notificationId).query(Integer.class).single();
    }
}
