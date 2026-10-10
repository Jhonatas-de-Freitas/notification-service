package io.github.jhonatasdefreitas.notifications;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.github.jhonatasdefreitas.notifications.domain.Channel;
import io.github.jhonatasdefreitas.notifications.domain.Message;
import io.github.jhonatasdefreitas.notifications.domain.NewNotification;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.Preferences;
import io.github.jhonatasdefreitas.notifications.domain.Recipient;
import io.github.jhonatasdefreitas.notifications.domain.Template;

public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-10-10T14:00:00Z");
    public static final Recipient ANA = new Recipient("Ana Souza", "ana@exemplo.com", "+5511999990002", "device-abc");
    public static final Preferences EVERYTHING_ON = new Preferences("c-1001", true, true, true);

    private Fixtures() {
    }

    public static NewNotification request(Recipient recipient, Instant scheduledAt) {
        return new NewNotification("c-1001", null, recipient, null, Template.GENERIC,
                Map.of("title", "Olá", "message", "Texto livre"), scheduledAt);
    }

    public static Notification pending(Channel channel, Recipient recipient) {
        return scheduled(channel, recipient, null);
    }

    /** Created at {@link #NOW}, to leave at the given time. */
    public static Notification scheduled(Channel channel, Recipient recipient, Instant scheduledAt) {
        return new Notification(UUID.randomUUID(), request(recipient, scheduledAt), channel,
                new Message("Olá", "Texto livre"), EVERYTHING_ON, NOW);
    }
}
