package io.github.jhonatasdefreitas.notifications.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.jhonatasdefreitas.notifications.domain.NotificationRequest;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, UUID> {

    Optional<NotificationRequest> findByIdempotencyKey(String idempotencyKey);
}
