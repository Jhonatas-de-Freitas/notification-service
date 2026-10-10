package io.github.jhonatasdefreitas.notifications.api;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.NewNotification;
import io.github.jhonatasdefreitas.notifications.domain.Recipient;
import io.github.jhonatasdefreitas.notifications.domain.Template;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record SendNotificationRequest(
        @NotBlank(message = "is required")
        @Size(max = 100, message = "must have at most 100 characters")
        String userId,

        UUID orderId,

        @NotNull(message = "is required")
        @Valid
        RecipientData recipient,

        @Size(min = 1, message = "must have at least one channel when present")
        Set<@NotNull(message = "must not be null") Channel> channels,

        @NotNull(message = "is required")
        Template template,

        Map<String, Object> params,

        Instant scheduledAt) {

    record RecipientData(
            @NotBlank(message = "is required")
            @Size(max = 120, message = "must have at most 120 characters")
            String name,

            @Email(message = "must be a valid e-mail address")
            @Size(max = 254, message = "must have at most 254 characters")
            String email,

            @Pattern(regexp = "\\+?[0-9]{8,15}", message = "must have 8 to 15 digits, with an optional leading +")
            String phone,

            @Size(max = 255, message = "must have at most 255 characters")
            String deviceToken) {
    }

    NewNotification toDomain() {
        return new NewNotification(userId.strip(), orderId,
                new Recipient(recipient.name().strip(), recipient.email(), recipient.phone(), recipient.deviceToken()),
                channels, template, params, scheduledAt);
    }
}
