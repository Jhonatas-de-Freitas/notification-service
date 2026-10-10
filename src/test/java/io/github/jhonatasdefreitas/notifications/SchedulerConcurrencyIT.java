package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jhonatasdefreitas.notifications.application.NotificationScheduler;
import io.github.jhonatasdefreitas.notifications.domain.Notification;
import io.github.jhonatasdefreitas.notifications.messaging.RabbitTopology;
import io.github.jhonatasdefreitas.notifications.persistence.NotificationRepository;
import tools.jackson.databind.JsonNode;

/** Two instances of the service share the database. Here the second one is a second thread. */
class SchedulerConcurrencyIT extends IntegrationTest {

    @Autowired
    NotificationScheduler scheduler;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    RabbitListenerEndpointRegistry listeners;

    @Test
    void aSecondInstanceTakesTheRowsTheFirstOneIsNotHoldingWithoutWaiting() throws Exception {
        // An hour ahead, so the scheduler of the application leaves these alone
        // while the test plays the two instances with a clock of its own.
        List<String> created = scheduleSms(6, Instant.now().plus(1, ChronoUnit.HOURS));
        Instant twoHoursFromNow = Instant.now().plus(2, ChronoUnit.HOURS);

        try (ExecutorService secondInstance = Executors.newSingleThreadExecutor()) {
            Callable<List<String>> takeEverythingDue = () -> transactions.execute(
                    second -> idsOf(notifications.lockDue(twoHoursFromNow, 1_000)));

            transactions.executeWithoutResult(first -> {
                List<String> heldByFirst = idsOf(notifications.lockDue(twoHoursFromNow, 3));
                // The first transaction is still open, with its three rows locked.
                List<String> takenBySecond = resultOf(secondInstance.submit(takeEverythingDue));

                assertThat(heldByFirst).hasSize(3);
                assertThat(takenBySecond).doesNotContainAnyElementsOf(heldByFirst);
                List<String> takenByEither = new ArrayList<>(heldByFirst);
                takenByEither.addAll(takenBySecond);
                assertThat(takenByEither).containsAll(created);
            });
        }
    }

    @Test
    void schedulersRacingForTheSameNotificationsQueueEachOneOnce() throws Exception {
        MessageListenerContainer smsConsumers = listeners.getListenerContainer("sms");
        List<String> created;
        List<String> queued;

        // With nobody consuming, whatever is queued stays in the queue to be counted.
        smsConsumers.stop();
        try {
            Instant scheduledAt = Instant.now().plusSeconds(4);
            created = scheduleSms(40, scheduledAt);

            // Two more schedulers on top of the one the application already runs,
            // calling without pause from before the time until after it.
            Instant enough = scheduledAt.plusSeconds(1);
            Callable<Void> instance = () -> {
                while (Instant.now().isBefore(enough)) {
                    scheduler.releaseDue();
                }
                return null;
            };
            try (ExecutorService instances = Executors.newFixedThreadPool(2)) {
                for (Future<Void> finished : instances.invokeAll(List.of(instance, instance))) {
                    resultOf(finished);
                }
            }

            assertThat(created).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("PENDING"));
            created.forEach(this::awaitPublished);
            queued = drain(RabbitTopology.SMS_QUEUE);
        } finally {
            smsConsumers.start();
        }

        assertThat(queued).filteredOn(created::contains).containsExactlyInAnyOrderElementsOf(created);
    }

    private List<String> scheduleSms(int count, Instant scheduledAt) {
        String userId = newUserWithEveryChannelOn();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> request = generic(userId, smsRecipient("+5511999990002"), "SMS");
            request.put("scheduledAt", scheduledAt.toString());
            JsonNode created = send(request).get("notifications").get(0);
            assertThat(created.get("status").asString()).isEqualTo("SCHEDULED");
            ids.add(created.get("id").asString());
        }
        return ids;
    }

    private List<String> drain(String queue) {
        List<String> bodies = new ArrayList<>();
        Message message;
        while ((message = rabbit.receive(queue, 500)) != null) {
            bodies.add(new String(message.getBody(), StandardCharsets.UTF_8));
        }
        return bodies;
    }

    private static List<String> idsOf(List<Notification> locked) {
        return locked.stream().map(notification -> notification.getId().toString()).toList();
    }

    private static <T> T resultOf(Future<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("The other instance did not finish", e);
        }
    }
}
