package io.github.jhonatasdefreitas.notifications.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.github.jhonatasdefreitas.notifications.domain.Preferences;

// No save() on purpose: the upsert is the only way to write, and it is the one that fills updated_at.
public interface PreferencesRepository extends Repository<Preferences, String> {

    Optional<Preferences> findById(String userId);

    // A single statement, so two first-time updates for the same user cannot collide on the primary key.
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            insert into notification_preferences (user_id, email, sms, push, updated_at)
            values (:userId, :email, :sms, :push, now())
            on conflict (user_id) do update
               set email = excluded.email, sms = excluded.sms, push = excluded.push, updated_at = excluded.updated_at
            """)
    void upsert(@Param("userId") String userId, @Param("email") boolean email, @Param("sms") boolean sms,
            @Param("push") boolean push);
}
