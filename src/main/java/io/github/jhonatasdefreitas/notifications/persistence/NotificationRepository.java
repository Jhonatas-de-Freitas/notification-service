package io.github.jhonatasdefreitas.notifications.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.domain.NotificationStatus;
import jakarta.persistence.LockModeType;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    List<Notification> findByRequestId(UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.id = :id")
    Optional<Notification> findByIdForUpdate(@Param("id") UUID id);

    /** Every argument is optional: a null one does not filter. */
    static Specification<Notification> matching(String userId, UUID orderId, NotificationStatus status) {
        Specification<Notification> filter = Specification.unrestricted();
        if (userId != null) {
            filter = filter.and((root, query, builder) -> builder.equal(root.get("userId"), userId));
        }
        if (orderId != null) {
            filter = filter.and((root, query, builder) -> builder.equal(root.get("orderId"), orderId));
        }
        if (status != null) {
            filter = filter.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        return filter;
    }
}
