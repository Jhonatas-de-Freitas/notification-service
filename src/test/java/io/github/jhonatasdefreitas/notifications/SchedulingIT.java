package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class SchedulingIT extends IntegrationTest {

    @Test
    void aScheduledEmailWaitsForItsTimeAndIsSentAfterIt() {
        String email = newEmail();
        Instant scheduledAt = Instant.now().plusSeconds(4).truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> request = generic(newUser(), emailRecipient(email), "EMAIL");
        request.put("scheduledAt", scheduledAt.toString());

        JsonNode created = send(request).get("notifications").get(0);
        String id = created.get("id").asString();

        assertThat(created.get("status").asString()).isEqualTo("SCHEDULED");
        // Half of the wait goes by and nothing has left.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            JsonNode waiting = notification(id);
            assertThat(waiting.get("status").asString()).isEqualTo("SCHEDULED");
            assertThat(waiting.get("attempts")).isEmpty();
            assertThat(mailbox.messagesTo(email)).isEmpty();
        });

        JsonNode sent = awaitStatus(id, "SENT");
        assertThat(successes(sent)).containsExactly(true);
        assertThat(Instant.parse(sent.get("scheduledAt").asString())).isEqualTo(scheduledAt);
        assertThat(Instant.parse(sent.get("attempts").get(0).get("at").asString())).isAfterOrEqualTo(scheduledAt);
        assertThat(mailbox.messagesTo(email)).hasSize(1);
    }

    @Test
    void aTimeAlreadyPastIsSentRightAway() {
        Map<String, Object> request = generic(newUser(), emailRecipient(newEmail()), "EMAIL");
        request.put("scheduledAt", Instant.now().minusSeconds(60).toString());

        JsonNode created = send(request).get("notifications").get(0);

        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        awaitStatus(created.get("id").asString(), "SENT");
    }

    @Test
    void aScheduledNotificationIsFoundByItsStatusWhileItWaits() {
        String userId = newUser();
        Map<String, Object> request = generic(userId, emailRecipient(newEmail()), "EMAIL");
        request.put("scheduledAt", Instant.now().plus(1, ChronoUnit.HOURS).toString());

        String id = sendOne(request);

        JsonNode found = list("userId=" + userId + "&status=SCHEDULED");
        assertThat(found.get("totalElements").asInt()).isEqualTo(1);
        assertThat(found.get("content").get(0).get("id").asString()).isEqualTo(id);
        assertThat(list("userId=" + userId + "&status=PENDING").get("totalElements").asInt()).isZero();
    }
}
