package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class DeliveryIT extends IntegrationTest {

    @Test
    void anEmailIsAcceptedAtOnceAndArrivesInTheInbox() {
        String email = newEmail();

        JsonNode accepted = send(generic(newUser(), emailRecipient(email), "EMAIL"));

        JsonNode created = accepted.get("notifications").get(0);
        assertThat(accepted.get("requestId").asString()).isNotBlank();
        assertThat(created.get("channel").asString()).isEqualTo("EMAIL");
        assertThat(created.get("status").asString()).isEqualTo("PENDING");

        JsonNode sent = awaitStatus(created.get("id").asString(), "SENT");
        assertThat(successes(sent)).containsExactly(true);
        assertThat(sent.get("sentAt").isNull()).isFalse();
        assertThat(sent.get("subject").asString()).isEqualTo("Aviso da loja");

        assertThat(mailbox.messagesTo(email)).singleElement().satisfies(message -> {
            assertThat(message.get("Subject").asString()).isEqualTo("Aviso da loja");
            assertThat(message.get("From").get("Address").asString()).isEqualTo("loja@exemplo.com");
            assertThat(message.get("To").get(0).get("Name").asString()).isEqualTo("Ana Souza");
            assertThat(mailbox.htmlOf(message)).contains("Olá, Ana Souza!", "Seu cupom chegou.");
        });
    }

    @Test
    void theOrderConfirmedEmailShowsTheOrderNumberItemsAndTotal() {
        String email = newEmail();
        UUID orderId = UUID.randomUUID();
        String orderNumber = "#" + orderId.toString().substring(0, 8).toUpperCase();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", newUser());
        request.put("orderId", orderId.toString());
        request.put("recipient", emailRecipient(email));
        request.put("channels", List.of("EMAIL"));
        request.put("template", "ORDER_CONFIRMED");
        request.put("params", Map.of(
                "totalAmount", 699.80,
                "items", List.of(Map.of("productName", "Teclado Mecânico", "quantity", 2, "unitPrice", 349.90))));

        JsonNode sent = awaitStatus(sendOne(request), "SENT");

        assertThat(sent.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(sent.get("template").asString()).isEqualTo("ORDER_CONFIRMED");
        assertThat(mailbox.messagesTo(email)).singleElement().satisfies(message -> {
            assertThat(message.get("Subject").asString()).isEqualTo("Seu pedido " + orderNumber + " foi confirmado");
            assertThat(mailbox.htmlOf(message)).contains("Teclado Mecânico", "R$ 349,90", "R$ 699,80");
        });
    }

    @Test
    void smsAndPushAreDeliveredByTheirOwnQueues() {
        String userId = newUserWithEveryChannelOn();
        Map<String, String> recipient = Map.of("name", "Ana Souza", "phone", "+5511999990002", "deviceToken", "device-abc");

        JsonNode created = send(generic(userId, recipient, "SMS", "PUSH")).get("notifications");

        assertThat(created).hasSize(2);
        for (JsonNode notification : created) {
            JsonNode sent = awaitStatus(notification.get("id").asString(), "SENT");
            assertThat(successes(sent)).containsExactly(true);
        }
    }
}
