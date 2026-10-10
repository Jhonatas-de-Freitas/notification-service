package io.github.jhonatasdefreitas.notifications.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.NotificationStatus;
import io.github.jhonatasdefreitas.notifications.domain.Recipient;
import io.github.jhonatasdefreitas.notifications.domain.SkipReason;
import io.github.jhonatasdefreitas.notifications.domain.Template;

record NotificationResponse(
        UUID id,
        String userId,
        UUID orderId,
        Channel channel,
        Template template,
        Recipient recipient,
        String subject,
        NotificationStatus status,
        SkipReason skipReason,
        List<AttemptResponse> attempts,
        Instant scheduledAt,
        Instant sentAt,
        Instant createdAt) {

    record AttemptResponse(int number, Instant at, boolean success, String error) {
    }

    static NotificationResponse of(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getUserId(),
                notification.getOrderId(),
                notification.getChannel(),
                notification.getTemplate(),
                notification.getRecipient(),
                notification.getSubject(),
                notification.getStatus(),
                notification.getSkipReason(),
                notification.getAttempts().stream()
                        .map(attempt -> new AttemptResponse(attempt.getNumber(), attempt.getAt(), attempt.isSuccess(),
                                attempt.getError()))
                        .toList(),
                notification.getScheduledAt(),
                notification.getSentAt(),
                notification.getCreatedAt());
    }
}
