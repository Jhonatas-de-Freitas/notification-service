package io.github.jhonatasdefreitas.notifications.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    // SKIP LOCKED lets a second instance take the next rows instead of waiting for the ones being published.
    @Query(nativeQuery = true, value = "select * from outbox order by id limit :limit for update skip locked")
    List<OutboxMessage> lockNextBatch(@Param("limit") int limit);
}
