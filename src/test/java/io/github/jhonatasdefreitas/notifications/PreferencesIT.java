package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class PreferencesIT extends IntegrationTest {

    @Test
    void aUserWhoNeverConfiguredGetsEmailAndPush() {
        client.get().uri("/preferences/{userId}", newUser()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(true)
                .jsonPath("$.sms").isEqualTo(false)
                .jsonPath("$.push").isEqualTo(true);
    }

    @Test
    void anUpdateIsStoredAndCanBeChangedAgain() {
        String userId = newUser();

        setPreferences(userId, false, true, false);
        setPreferences(userId, false, true, true);

        client.get().uri("/preferences/{userId}", userId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.email").isEqualTo(false)
                .jsonPath("$.sms").isEqualTo(true)
                .jsonPath("$.push").isEqualTo(true);
    }

    @Test
    void aChannelTurnedOffIsSkippedAndRecordedWithTheReason() {
        String userId = newUser();

        JsonNode created = send(generic(userId, everyAddress(newEmail()), "EMAIL", "SMS", "PUSH")).get("notifications");

        assertThat(statusByChannel(created)).containsExactly(
                Map.entry("EMAIL", "PENDING"), Map.entry("SMS", "SKIPPED"), Map.entry("PUSH", "PENDING"));
        JsonNode sms = notification(created.get(1).get("id").asString());
        assertThat(sms.get("skipReason").asString()).isEqualTo("CHANNEL_DISABLED");
        assertThat(sms.get("attempts")).isEmpty();
    }

    @Test
    void changingThePreferenceChangesTheNextRequest() {
        String userId = newUser();
        String email = newEmail();
        String smsBefore = send(generic(userId, everyAddress(email), "SMS")).get("notifications").get(0).get("id").asString();
        assertThat(statusOf(smsBefore)).isEqualTo("SKIPPED");

        setPreferences(userId, false, true, true);
        JsonNode created = send(generic(userId, everyAddress(email), "EMAIL", "SMS")).get("notifications");

        assertThat(statusByChannel(created)).containsExactly(Map.entry("EMAIL", "SKIPPED"), Map.entry("SMS", "PENDING"));
        awaitStatus(created.get(1).get("id").asString(), "SENT");
        assertThat(statusOf(smsBefore)).isEqualTo("SKIPPED");
        assertThat(mailbox.messagesTo(email)).isEmpty();
    }

    @Test
    void withoutChannelsTheRequestGoesToTheOnesThePreferenceAllows() {
        String userId = newUser();

        JsonNode byDefault = send(generic(userId, everyAddress(newEmail()))).get("notifications");
        setPreferences(userId, false, true, false);
        JsonNode afterUpdate = send(generic(userId, everyAddress(newEmail()))).get("notifications");

        assertThat(statusByChannel(byDefault)).containsExactly(Map.entry("EMAIL", "PENDING"), Map.entry("PUSH", "PENDING"));
        assertThat(statusByChannel(afterUpdate)).containsExactly(Map.entry("SMS", "PENDING"));
    }

    @Test
    void aChannelWithoutAnAddressIsSkipped() {
        String userId = newUserWithEveryChannelOn();

        JsonNode created = send(generic(userId, emailRecipient(newEmail()), "EMAIL", "SMS")).get("notifications");

        assertThat(statusByChannel(created)).containsExactly(Map.entry("EMAIL", "PENDING"), Map.entry("SMS", "SKIPPED"));
        assertThat(notification(created.get(1).get("id").asString()).get("skipReason").asString())
                .isEqualTo("MISSING_DESTINATION");
    }

    private static Map<String, String> everyAddress(String email) {
        return Map.of("name", "Ana Souza", "email", email, "phone", "+5511999990002", "deviceToken", "device-abc");
    }

    private static List<Map.Entry<String, String>> statusByChannel(JsonNode notifications) {
        return notifications.valueStream()
                .map(notification -> Map.entry(notification.get("channel").asString(), notification.get("status").asString()))
                .toList();
    }
}
