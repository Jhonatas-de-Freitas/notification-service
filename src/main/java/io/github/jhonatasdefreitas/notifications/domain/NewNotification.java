package io.github.jhonatasdefreitas.notifications.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a caller asks for. {@code channels} is null when the caller leaves the choice
 * to the user's preferences.
 */
public record NewNotification(
        String userId,
        UUID orderId,
        Recipient recipient,
        Set<Channel> channels,
        Template template,
        Map<String, Object> params,
        Instant scheduledAt) {

    public NewNotification {
        params = params == null ? Map.of() : params;
    }
}
