package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import tools.jackson.databind.JsonNode;

/** The test stands in for the order service: it publishes to orders.events what that service would. */
class OrderEventsIT extends IntegrationTest {

    private static final String ORDER_EVENTS = "orders.events";
    private static final String DEAD_LETTERS = "notification-service.dlt";

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumers;

    @Test
    void anOrderConfirmedEventSendsTheConfirmationToTheCustomer() {
        String customerId = newUser();
        String email = newEmail();
        UUID orderId = UUID.randomUUID();

        publish("OrderConfirmed", orderId, orderConfirmed(customerId, email));

        JsonNode byEmail = awaitStatus(awaitNotification(orderId, "ORDER_CONFIRMED", "EMAIL"), "SENT");
        assertThat(byEmail.get("userId").asString()).isEqualTo(customerId);
        assertThat(byEmail.get("recipient").get("name").asString()).isEqualTo("Ana Souza");
        assertThat(byEmail.get("recipient").get("email").asString()).isEqualTo(email);
        assertThat(mailbox.messagesTo(email)).singleElement().satisfies(message -> {
            assertThat(message.get("Subject").asString()).isEqualTo("Seu pedido " + numberOf(orderId) + " foi confirmado");
            assertThat(mailbox.htmlOf(message)).contains("Teclado Mecânico", "R$ 349,90", "R$ 699,80");
        });

        JsonNode byPush = awaitStatus(awaitNotification(orderId, "ORDER_CONFIRMED", "PUSH"), "SENT");
        assertThat(byPush.get("recipient").get("deviceToken").asString()).isEqualTo("device-" + customerId);
    }

    @Test
    void theSameEventDeliveredTwiceSendsOneEmail() {
        String customerId = newUser();
        String email = newEmail();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish(eventId, "OrderConfirmed", orderId, orderConfirmed(customerId, email));
        publish(eventId, "OrderConfirmed", orderId, orderConfirmed(customerId, email));
        // Same key, same partition: when this one has been handled, so have the two copies before it.
        publish("OrderCancelled", orderId, orderCancelled(customerId, email));

        awaitStatus(awaitNotification(orderId, "ORDER_CANCELLED", "EMAIL"), "SENT");
        awaitStatus(awaitNotification(orderId, "ORDER_CONFIRMED", "EMAIL"), "SENT");
        assertThat(notificationsOf(orderId))
                .extracting(notification -> notification.get("template").asString() + " " + notification.get("channel").asString())
                .containsExactlyInAnyOrder("ORDER_CONFIRMED EMAIL", "ORDER_CONFIRMED PUSH",
                        "ORDER_CANCELLED EMAIL", "ORDER_CANCELLED PUSH");
        assertThat(mailbox.messagesTo(email))
                .extracting(message -> message.get("Subject").asString())
                .containsExactlyInAnyOrder("Seu pedido " + numberOf(orderId) + " foi confirmado",
                        "Seu pedido " + numberOf(orderId) + " foi cancelado");
    }

    @Test
    void orderCreatedAndOrderCancelledUseTheirOwnTexts() {
        String customerId = newUser();
        String email = newEmail();
        UUID orderId = UUID.randomUUID();

        publish("OrderCreated", orderId, orderCreated(customerId, email));
        publish("OrderCancelled", orderId, orderCancelled(customerId, email));

        awaitStatus(awaitNotification(orderId, "ORDER_RECEIVED", "EMAIL"), "SENT");
        awaitStatus(awaitNotification(orderId, "ORDER_CANCELLED", "EMAIL"), "SENT");
        List<JsonNode> inbox = mailbox.messagesTo(email);
        assertThat(inbox).extracting(message -> message.get("Subject").asString()).containsExactlyInAnyOrder(
                "Recebemos o seu pedido " + numberOf(orderId), "Seu pedido " + numberOf(orderId) + " foi cancelado");
        assertThat(inbox)
                .filteredOn(message -> message.get("Subject").asString().endsWith("cancelado"))
                .singleElement()
                .satisfies(message -> assertThat(mailbox.htmlOf(message))
                        .contains("um ou mais itens estão sem estoque", "Headset USB: pedido 1, disponível 0"));
    }

    @Test
    void aChannelTheCustomerTurnedOffIsSkipped() {
        String customerId = newUser();
        UUID orderId = UUID.randomUUID();
        setPreferences(customerId, true, false, false);

        publish("OrderCreated", orderId, orderCreated(customerId, newEmail()));

        awaitStatus(awaitNotification(orderId, "ORDER_RECEIVED", "EMAIL"), "SENT");
        JsonNode byPush = notification(awaitNotification(orderId, "ORDER_RECEIVED", "PUSH"));
        assertThat(byPush.get("status").asString()).isEqualTo("SKIPPED");
        assertThat(byPush.get("skipReason").asString()).isEqualTo("CHANNEL_DISABLED");
    }

    @Test
    void whatCannotBeReadGoesToTheDeadLetterTopicAndTheEventsBehindItAreHandled() {
        String customerId = newUser();
        UUID orderId = UUID.randomUUID();

        // All with the same key, so they sit in this order on the same partition.
        kafka.send(ORDER_EVENTS, orderId.toString(), "this is not json").join();
        publish("OrderShipped", orderId, "{\"carrier\":\"Correios\"}");
        publish("OrderConfirmed", orderId, "{\"totalAmount\":699.80}");
        publish("OrderCreated", orderId, orderCreated(customerId, newEmail()));

        awaitStatus(awaitNotification(orderId, "ORDER_RECEIVED", "EMAIL"), "SENT");
        // Nothing for the type it does not know, nothing for the confirmation without a customer.
        assertThat(notificationsOf(orderId)).extracting(notification -> notification.get("template").asString())
                .containsExactly("ORDER_RECEIVED", "ORDER_RECEIVED");

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(deadLetters(orderId)).hasSize(2));
        List<ConsumerRecord<String, String>> deadLetters = deadLetters(orderId);
        assertThat(deadLetters.get(0).value()).isEqualTo("this is not json");
        assertThat(header(deadLetters.get(0), "kafka_dlt-exception-message")).contains("Not a valid event");
        assertThat(deadLetters.get(1).value()).contains("\"type\":\"OrderConfirmed\"");
        assertThat(header(deadLetters.get(1), "kafka_dlt-exception-message")).contains("customerId is required");
        assertThat(deadLetters).allSatisfy(deadLetter -> {
            assertThat(header(deadLetter, "kafka_dlt-original-topic")).isEqualTo(ORDER_EVENTS);
            assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidEventException");
        });
    }

    // The payloads below are the examples of the contract.

    private static String orderCreated(String customerId, String email) {
        return """
                {"customerId":"%s","customerName":"Ana Souza","customerEmail":"%s",
                 "items":[{"productId":"11111111-1111-1111-1111-111111111111","quantity":2}]}
                """.formatted(customerId, email);
    }

    private static String orderConfirmed(String customerId, String email) {
        return """
                {"customerId":"%s","customerName":"Ana Souza","customerEmail":"%s","totalAmount":699.80,
                 "items":[{"productId":"11111111-1111-1111-1111-111111111111","productName":"Teclado Mecânico",
                           "quantity":2,"unitPrice":349.90}]}
                """.formatted(customerId, email);
    }

    private static String orderCancelled(String customerId, String email) {
        return """
                {"customerId":"%s","customerName":"Ana Souza","customerEmail":"%s",
                 "reason":"OUT_OF_STOCK","detail":"Headset USB: pedido 1, disponível 0"}
                """.formatted(customerId, email);
    }

    private void publish(String type, UUID orderId, String payload) {
        publish(UUID.randomUUID(), type, orderId, payload);
    }

    /** With the event id chosen by the caller, to deliver the very same event more than once. */
    private void publish(UUID eventId, String type, UUID orderId, String payload) {
        String envelope = """
                {"eventId":"%s","type":"%s","orderId":"%s","occurredAt":"%s","payload":%s}
                """.formatted(eventId, type, orderId, Instant.now(), payload);
        ProducerRecord<String, String> record = new ProducerRecord<>(ORDER_EVENTS, orderId.toString(), envelope);
        record.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();
    }

    private List<JsonNode> notificationsOf(UUID orderId) {
        return list("orderId=" + orderId).get("content").valueStream().toList();
    }

    /** Waits for the notification an event should create and returns its id. */
    private String awaitNotification(UUID orderId, String template, String channel) {
        String[] id = new String[1];
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> {
            notificationsOf(orderId).stream()
                    .filter(notification -> notification.get("template").asString().equals(template)
                            && notification.get("channel").asString().equals(channel))
                    .findFirst()
                    .ifPresent(notification -> id[0] = notification.get("id").asString());
            return id[0] != null;
        });
        return id[0];
    }

    private static String numberOf(UUID orderId) {
        return "#" + orderId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** Everything in the dead letter topic so far for one key, in the order it was written. */
    private List<ConsumerRecord<String, String>> deadLetters(UUID key) {
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), null)) {
            List<TopicPartition> partitions = consumer.partitionsFor(DEAD_LETTERS).stream()
                    .map(partition -> new TopicPartition(DEAD_LETTERS, partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (key.toString().equals(record.key())) {
                        found.add(record);
                    }
                }
            }
            return found;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
