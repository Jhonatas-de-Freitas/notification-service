package io.github.jhonatasdefreitas.notifications.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Content;
import io.github.jhonatasdefreitas.notifications.domain.MessageTemplates;
import io.github.jhonatasdefreitas.notifications.domain.NewNotification;
import io.github.jhonatasdefreitas.notifications.domain.Recipient;
import io.github.jhonatasdefreitas.notifications.domain.Template;

class OrderEventTest {

    private static final String EVENT_ID = "0b9d6c1e-4a7b-4f0e-9a51-0c6f3d2a8e11";
    private static final String ORDER_ID = "7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c";

    // The examples of the contract, as the order service publishes them.
    private static final String ORDER_CREATED = """
            {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
             "items":[{"productId":"11111111-1111-1111-1111-111111111111","quantity":2}]}
            """;
    private static final String ORDER_CONFIRMED = """
            {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
             "totalAmount":699.80,
             "items":[{"productId":"11111111-1111-1111-1111-111111111111","productName":"Teclado Mecânico",
                       "quantity":2,"unitPrice":349.90}]}
            """;
    private static final String ORDER_CANCELLED = """
            {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
             "reason":"OUT_OF_STOCK","detail":"Headset USB: pedido 1, disponível 0"}
            """;

    @ParameterizedTest
    @CsvSource({
            "OrderCreated, ORDER_RECEIVED",
            "OrderConfirmed, ORDER_CONFIRMED",
            "OrderCancelled, ORDER_CANCELLED"
    })
    void eachOrderEventHasItsTemplate(String type, Template template) {
        NewNotification notification = event(type, ORDER_CREATED).toNotification().orElseThrow();

        assertThat(notification.template()).isEqualTo(template);
    }

    @Test
    void theCustomerOfTheEventIsTheRecipientByEmailAndPush() {
        NewNotification notification = event("OrderConfirmed", ORDER_CONFIRMED).toNotification().orElseThrow();

        assertThat(notification.userId()).isEqualTo("c-1001");
        assertThat(notification.orderId()).isEqualTo(UUID.fromString(ORDER_ID));
        assertThat(notification.recipient()).isEqualTo(new Recipient("Ana Souza", "ana@exemplo.com", null, "device-c-1001"));
        assertThat(notification.channels()).containsExactly(Channel.EMAIL, Channel.PUSH);
        assertThat(notification.scheduledAt()).isNull();
    }

    @Test
    void thePayloadFillsTheConfirmationWithItemsAndTotal() {
        NewNotification notification = event("OrderConfirmed", ORDER_CONFIRMED).toNotification().orElseThrow();

        Content content = MessageTemplates.compose(notification.template(), notification.orderId(), notification.params());

        assertThat(content.subject()).isEqualTo("Seu pedido #7C1F0A34 foi confirmado");
        assertThat(content.total()).isEqualByComparingTo("699.80");
        assertThat(content.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("Teclado Mecânico");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.unitPrice()).isEqualByComparingTo("349.90");
        });
    }

    @Test
    void thePayloadFillsTheCancellationWithReasonAndDetail() {
        NewNotification notification = event("OrderCancelled", ORDER_CANCELLED).toNotification().orElseThrow();

        Content content = MessageTemplates.compose(notification.template(), notification.orderId(), notification.params());

        assertThat(content.subject()).isEqualTo("Seu pedido #7C1F0A34 foi cancelado");
        assertThat(content.paragraphs()).contains("Motivo: um ou mais itens estão sem estoque.",
                "Detalhe: Headset USB: pedido 1, disponível 0");
    }

    @Test
    void theSameEventAlwaysGivesTheSameIdempotencyKey() {
        assertThat(event("OrderCreated", ORDER_CREATED).idempotencyKey())
                .isEqualTo(event("OrderCreated", ORDER_CREATED).idempotencyKey())
                .isEqualTo("event:" + EVENT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OrderShipped", "StockReserved", "PaymentApproved"})
    void aTypeWithNothingToTellTheCustomerGivesNoNotification(String type) {
        // Whatever the payload is: a type that is ignored is not looked into.
        assertThat(event(type, "{\"anything\":true}").toNotification()).isEmpty();
        assertThat(event(type, "\"not even an object\"").toNotification()).isEmpty();
    }

    @Test
    void ignoresFieldsItDoesNotKnow() {
        OrderEvent event = OrderEvent.parse("""
                {"eventId":"%s","type":"OrderCreated","orderId":"%s","occurredAt":"2026-10-10T14:03:22.123Z",
                 "traceId":"abc","payload":{"customerId":"c-1001","customerName":"Ana Souza",
                 "customerEmail":"ana@exemplo.com","items":[],"coupon":"SEXTA10"}}
                """.formatted(EVENT_ID, ORDER_ID));

        assertThat(event.toNotification()).isPresent();
    }

    @Test
    void aCustomerWithoutEmailOrNameIsStillNotified() {
        NewNotification notification = event("OrderCreated", "{\"customerId\":\"c-1001\"}").toNotification().orElseThrow();

        assertThat(notification.recipient()).isEqualTo(new Recipient("Cliente", null, null, "device-c-1001"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "this is not json",
            "null",
            "42",
            "{\"type\":\"OrderCreated\"",
            "{\"type\":\"OrderCreated\",\"orderId\":\"7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c\"}",
            "{\"eventId\":\"0b9d6c1e-4a7b-4f0e-9a51-0c6f3d2a8e11\",\"type\":\"OrderCreated\",\"orderId\":\"not-a-uuid\"}"
    })
    void refusesWhatIsNotAnEvent(String message) {
        assertThatThrownBy(() -> OrderEvent.parse(message))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageStartingWith("Not a valid event");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "[]",
            "\"text\"",
            "{}",
            "{\"customerId\":\" \",\"customerEmail\":\"ana@exemplo.com\"}",
            "{\"customerId\":1001}"
    })
    void refusesAnOrderEventWithoutACustomer(String payload) {
        OrderEvent event = event("OrderConfirmed", payload);

        assertThatThrownBy(event::toNotification)
                .isInstanceOf(InvalidEventException.class)
                .hasMessageStartingWith("Not a valid OrderConfirmed payload");
    }

    @Test
    void refusesAnOrderEventWithoutPayload() {
        OrderEvent event = OrderEvent.parse("""
                {"eventId":"%s","type":"OrderConfirmed","orderId":"%s"}
                """.formatted(EVENT_ID, ORDER_ID));

        assertThatThrownBy(event::toNotification).isInstanceOf(InvalidEventException.class);
    }

    private static OrderEvent event(String type, String payload) {
        return OrderEvent.parse("""
                {"eventId":"%s","type":"%s","orderId":"%s","occurredAt":"2026-10-10T14:03:22.123Z","payload":%s}
                """.formatted(EVENT_ID, type, ORDER_ID, payload));
    }
}
