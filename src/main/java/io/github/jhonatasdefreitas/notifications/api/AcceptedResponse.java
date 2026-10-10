package io.github.jhonatasdefreitas.notifications.api;

import java.util.List;
import java.util.UUID;

import io.github.jhonatasdefreitas.notifications.application.NotificationService.Accepted;
import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.NotificationStatus;

record AcceptedResponse(UUID requestId, List<Item> notifications) {

    record Item(UUID id, Channel channel, NotificationStatus status) {
    }

    static AcceptedResponse of(Accepted accepted) {
        return new AcceptedResponse(accepted.requestId(), accepted.notifications().stream()
                .map(notification -> new Item(notification.getId(), notification.getChannel(), notification.getStatus()))
                .toList());
    }
}
