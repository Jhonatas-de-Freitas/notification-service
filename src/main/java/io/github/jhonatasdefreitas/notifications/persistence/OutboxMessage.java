package io.github.jhonatasdefreitas.notifications.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private UUID notificationId;
    private String queue;
    private int attempt;
    private Instant createdAt;

    protected OutboxMessage() {
    }

    public OutboxMessage(UUID notificationId, String queue, int attempt, Instant createdAt) {
        this.notificationId = notificationId;
        this.queue = queue;
        this.attempt = attempt;
        this.createdAt = createdAt;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public String getQueue() {
        return queue;
    }

    public int getAttempt() {
        return attempt;
    }
}
