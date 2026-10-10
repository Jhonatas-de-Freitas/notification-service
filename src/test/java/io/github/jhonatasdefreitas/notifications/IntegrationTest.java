package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/**
 * The whole service against real PostgreSQL, RabbitMQ and Mailpit. Every integration test
 * shares this configuration, so the containers and the application start once. Tests keep
 * out of each other's way by using a user and an address of their own.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "notifications.retry.intervals=300ms,600ms,900ms",
        "notifications.outbox.poll-interval=100ms",
        "notifications.scheduler.poll-interval=100ms"
})
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
abstract class IntegrationTest {

    static final Duration FIRST_RETRY_INTERVAL = Duration.ofMillis(300);
    static final Duration SECOND_RETRY_INTERVAL = Duration.ofMillis(600);
    static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    RestTestClient client;

    @Autowired
    Mailbox mailbox;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry metrics;

    static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    static String newEmail() {
        return UUID.randomUUID() + "@exemplo.com";
    }

    static Map<String, Object> generic(String userId, Map<String, String> recipient, String... channels) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", userId);
        request.put("recipient", recipient);
        request.put("template", "GENERIC");
        request.put("params", Map.of("title", "Aviso da loja", "message", "Seu cupom chegou."));
        if (channels.length > 0) {
            request.put("channels", List.of(channels));
        }
        return request;
    }

    static Map<String, String> emailRecipient(String email) {
        return Map.of("name", "Ana Souza", "email", email);
    }

    static Map<String, String> smsRecipient(String phone) {
        return Map.of("name", "Ana Souza", "phone", phone);
    }

    static Map<String, String> pushRecipient(String deviceToken) {
        return Map.of("name", "Ana Souza", "deviceToken", deviceToken);
    }

    JsonNode send(Map<String, Object> request) {
        return send(request, null);
    }

    JsonNode send(Map<String, Object> request, String idempotencyKey) {
        RestTestClient.RequestBodySpec post = client.post().uri("/notifications").contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            post = post.header("Idempotency-Key", idempotencyKey);
        }
        return post.body(request).exchange()
                .expectStatus().isAccepted()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    /** Sends a request for a single channel and returns the id of the notification it created. */
    String sendOne(Map<String, Object> request) {
        JsonNode notifications = send(request).get("notifications");
        assertThat(notifications).hasSize(1);
        return notifications.get(0).get("id").asString();
    }

    JsonNode notification(String id) {
        return client.get().uri("/notifications/{id}", id).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    String statusOf(String id) {
        return notification(id).get("status").asString();
    }

    JsonNode awaitStatus(String id, String status) {
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> assertThat(statusOf(id)).isEqualTo(status));
        return notification(id);
    }

    JsonNode list(String query) {
        return client.get().uri("/notifications?" + query).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    void setPreferences(String userId, boolean email, boolean sms, boolean push) {
        client.put().uri("/preferences/{userId}", userId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "sms", sms, "push", push))
                .exchange()
                .expectStatus().isOk();
    }

    /** SMS is off for a user who never configured anything; tests about SMS turn it on first. */
    String newUserWithEveryChannelOn() {
        String userId = newUser();
        setPreferences(userId, true, true, true);
        return userId;
    }

    static List<Boolean> successes(JsonNode notification) {
        return notification.get("attempts").valueStream().map(attempt -> attempt.get("success").asBoolean()).toList();
    }

    /** Waits until the outbox row of the notification is gone, which means the broker has its message. */
    void awaitPublished(String notificationId) {
        await().atMost(PATIENCE).until(() -> jdbc.sql("select count(*) from outbox where notification_id = ?::uuid")
                .param(notificationId).query(Integer.class).single() == 0);
    }

    double deliveries(String channel, String result) {
        return metrics.counter("notifications.deliveries", "channel", channel, "result", result).count();
    }
}
