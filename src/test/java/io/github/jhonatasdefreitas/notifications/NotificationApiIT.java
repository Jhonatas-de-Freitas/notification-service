package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

class NotificationApiIT extends IntegrationTest {

    @Test
    void anInvalidRequestIsRefusedWithAProblemAndNothingIsStored() {
        String userId = newUser();
        Map<String, Object> request = generic(userId, Map.of("name", "", "email", "not-an-email"), "EMAIL");
        request.remove("template");

        client.post().uri("/notifications").contentType(MediaType.APPLICATION_JSON).body(request).exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo("The request has invalid fields")
                .jsonPath("$.instance").isEqualTo("/notifications")
                .jsonPath("$.errors[0].field").isEqualTo("recipient.email")
                .jsonPath("$.errors[1].field").isEqualTo("recipient.name")
                .jsonPath("$.errors[2].field").isEqualTo("template");

        assertThat(list("userId=" + userId).get("totalElements").asInt()).isZero();
    }

    @Test
    void aTemplateWithoutItsParametersIsRefused() {
        Map<String, Object> request = generic(newUser(), emailRecipient(newEmail()), "EMAIL");
        request.put("params", Map.of("title", "Aviso da loja"));

        client.post().uri("/notifications").contentType(MediaType.APPLICATION_JSON).body(request).exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("params.message");
    }

    @Test
    void aNotificationThatDoesNotExistIs404() {
        client.get().uri("/notifications/{id}", UUID.randomUUID()).exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void theListFiltersByUserOrderAndStatus() {
        String ana = newUser();
        String bia = newUser();
        UUID orderId = UUID.randomUUID();
        String generic = sendOne(generic(ana, emailRecipient(newEmail()), "EMAIL"));
        // SMS is off by default, so this one request leaves a SENT and a SKIPPED notification for the order.
        JsonNode aboutTheOrder = send(orderReceived(ana, orderId)).get("notifications");
        String orderEmail = aboutTheOrder.get(0).get("id").asString();
        String orderSms = aboutTheOrder.get(1).get("id").asString();
        String fromBia = sendOne(generic(bia, emailRecipient(newEmail()), "EMAIL"));
        awaitStatus(generic, "SENT");
        awaitStatus(orderEmail, "SENT");
        awaitStatus(fromBia, "SENT");

        assertThat(ids(list("userId=" + ana))).containsExactlyInAnyOrder(generic, orderEmail, orderSms);
        assertThat(ids(list("userId=" + bia))).containsExactly(fromBia);
        assertThat(ids(list("orderId=" + orderId))).containsExactlyInAnyOrder(orderEmail, orderSms);
        assertThat(ids(list("userId=" + ana + "&status=SENT"))).containsExactlyInAnyOrder(generic, orderEmail);
        assertThat(ids(list("userId=" + ana + "&status=SKIPPED"))).containsExactly(orderSms);
        assertThat(ids(list("orderId=" + orderId + "&status=SENT"))).containsExactly(orderEmail);
        assertThat(ids(list("userId=" + ana + "&status=FAILED"))).isEmpty();
    }

    @Test
    void aFilterLeftEmptyDoesNotFilter() {
        UUID orderId = UUID.randomUUID();
        JsonNode aboutTheOrder = send(orderReceived(newUser(), orderId)).get("notifications");

        JsonNode found = list("userId=&orderId=" + orderId + "&status=&page=&size=");

        assertThat(ids(found)).containsExactlyInAnyOrder(
                aboutTheOrder.get(0).get("id").asString(), aboutTheOrder.get(1).get("id").asString());
        assertThat(found.get("size").asInt()).isEqualTo(20);
    }

    @Test
    void theListIsPagedWithTheNewestFirst() {
        String userId = newUser();
        String first = sendOne(generic(userId, emailRecipient(newEmail()), "EMAIL"));
        String second = sendOne(generic(userId, emailRecipient(newEmail()), "EMAIL"));
        String third = sendOne(generic(userId, emailRecipient(newEmail()), "EMAIL"));

        JsonNode firstPage = list("userId=" + userId + "&page=0&size=2");
        JsonNode secondPage = list("userId=" + userId + "&page=1&size=2");

        assertThat(ids(firstPage)).containsExactly(third, second);
        assertThat(ids(secondPage)).containsExactly(first);
        assertThat(firstPage.get("page").asInt()).isZero();
        assertThat(firstPage.get("size").asInt()).isEqualTo(2);
        assertThat(firstPage.get("totalElements").asInt()).isEqualTo(3);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void aListEntryCarriesTheAttempts() {
        String userId = newUserWithEveryChannelOn();
        String id = sendOne(generic(userId, smsRecipient("+5511999990001"), "SMS"));
        awaitStatus(id, "SENT");

        JsonNode entry = list("userId=" + userId).get("content").get(0);

        assertThat(successes(entry)).containsExactly(false, false, true);
        assertThat(entry.get("recipient").get("phone").asString()).isEqualTo("+5511999990001");
    }

    private static Map<String, Object> orderReceived(String userId, UUID orderId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", userId);
        request.put("orderId", orderId.toString());
        request.put("recipient", Map.of("name", "Ana Souza", "email", newEmail(), "phone", "+5511999990002"));
        request.put("channels", List.of("EMAIL", "SMS"));
        request.put("template", "ORDER_RECEIVED");
        request.put("params", Map.of("items", List.of(Map.of("productId", UUID.randomUUID().toString(), "quantity", 2))));
        return request;
    }

    private static List<String> ids(JsonNode page) {
        return page.get("content").valueStream().map(notification -> notification.get("id").asString()).toList();
    }
}
