package io.github.jhonatasdefreitas.notifications.events;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.NewNotification;
import io.github.jhonatasdefreitas.notifications.domain.Recipient;
import io.github.jhonatasdefreitas.notifications.domain.Template;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The envelope the store's events travel in, with only the fields this service reads. */
record OrderEvent(UUID eventId, String type, UUID orderId, JsonNode payload) {

    // Its own mapper, so tuning the JSON of the REST API never changes how events are read.
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {
    };

    private static final Map<String, Template> TEMPLATES = Map.of(
            "OrderCreated", Template.ORDER_RECEIVED,
            "OrderConfirmed", Template.ORDER_CONFIRMED,
            "OrderCancelled", Template.ORDER_CANCELLED);

    OrderEvent {
        if (eventId == null || orderId == null || type == null || type.isBlank()) {
            throw new IllegalArgumentException("eventId, type and orderId are required");
        }
    }

    static OrderEvent parse(String json) {
        OrderEvent event;
        try {
            event = json == null ? null : JSON.readValue(json, OrderEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("Not a valid event: " + e.getOriginalMessage(), e);
        }
        if (event == null) {
            throw new InvalidEventException("Not a valid event: empty message");
        }
        return event;
    }

    /** The same event always gives the same key, which is what makes a second delivery create nothing. */
    String idempotencyKey() {
        return "event:" + eventId;
    }

    /**
     * What the customer should be told about this event, or empty for a type this service has
     * nothing to say about. The payload goes to the template as it came: the templates read the
     * same field names.
     */
    Optional<NewNotification> toNotification() {
        Template template = TEMPLATES.get(type);
        if (template == null) {
            return Optional.empty();
        }
        if (payload == null || !payload.isObject()) {
            throw new InvalidEventException("Not a valid " + type + " payload: an object is expected");
        }
        Map<String, Object> params = JSON.convertValue(payload, PARAMS);
        String customerId = text(params.get("customerId"));
        if (customerId == null) {
            throw new InvalidEventException("Not a valid " + type + " payload: customerId is required");
        }
        String customerName = text(params.get("customerName"));
        Recipient recipient = new Recipient(customerName == null ? "Cliente" : customerName,
                text(params.get("customerEmail")), null, "device-" + customerId);
        // Both channels are asked for; the one the customer turned off is recorded as skipped.
        return Optional.of(new NewNotification(customerId, orderId, recipient, EnumSet.of(Channel.EMAIL, Channel.PUSH),
                template, params, null));
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }
}
