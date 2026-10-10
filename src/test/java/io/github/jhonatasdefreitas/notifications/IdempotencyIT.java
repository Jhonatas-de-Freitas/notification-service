package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class IdempotencyIT extends IntegrationTest {

    @Test
    void theSameKeyTwiceCreatesOneNotificationAndSendsOneEmail() {
        String userId = newUser();
        String email = newEmail();
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = generic(userId, emailRecipient(email), "EMAIL");

        JsonNode first = send(request, key);
        String id = first.get("notifications").get(0).get("id").asString();
        awaitStatus(id, "SENT");
        JsonNode second = send(request, key);

        assertThat(second.get("requestId")).isEqualTo(first.get("requestId"));
        assertThat(second.get("notifications")).hasSize(1);
        assertThat(second.get("notifications").get(0).get("id").asString()).isEqualTo(id);
        assertThat(second.get("notifications").get(0).get("status").asString()).isEqualTo("SENT");
        assertThat(list("userId=" + userId).get("totalElements").asInt()).isEqualTo(1);
        assertThat(mailbox.messagesTo(email)).hasSize(1);
    }

    @Test
    void theSameKeyArrivingTogetherCreatesOneNotification() throws Exception {
        String userId = newUser();
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = generic(userId, emailRecipient(newEmail()), "EMAIL");
        Callable<JsonNode> call = () -> send(request, key);

        List<JsonNode> answers;
        try (ExecutorService callers = Executors.newFixedThreadPool(8)) {
            List<Future<JsonNode>> pending = callers.invokeAll(List.of(call, call, call, call, call, call, call, call));
            answers = pending.stream().map(IdempotencyIT::resultOf).toList();
        }

        assertThat(answers).extracting(answer -> answer.get("requestId").asString()).containsOnly(
                answers.getFirst().get("requestId").asString());
        assertThat(list("userId=" + userId).get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void withoutAKeyEveryCallCreatesANotification() {
        String userId = newUser();
        Map<String, Object> request = generic(userId, emailRecipient(newEmail()), "EMAIL");

        send(request);
        send(request);

        assertThat(list("userId=" + userId).get("totalElements").asInt()).isEqualTo(2);
    }

    private static JsonNode resultOf(Future<JsonNode> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError("A concurrent call failed", e);
        }
    }
}
