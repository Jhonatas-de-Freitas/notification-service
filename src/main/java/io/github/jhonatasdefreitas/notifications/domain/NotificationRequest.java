package io.github.jhonatasdefreitas.notifications.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One accepted call. It exists so a repeated idempotency key can find what the first call created. */
@Entity
@Table(name = "notification_requests")
public class NotificationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String idempotencyKey;
    private Instant createdAt;

    protected NotificationRequest() {
    }

    public NotificationRequest(String idempotencyKey, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }
}
