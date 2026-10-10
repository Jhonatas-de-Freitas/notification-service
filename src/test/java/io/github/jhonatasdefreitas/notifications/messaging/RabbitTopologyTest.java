package io.github.jhonatasdefreitas.notifications.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

import io.github.jhonatasdefreitas.notifications.domain.RetryPolicy;

class RabbitTopologyTest {

    private final Declarables declarables = declare(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45));
    private final Map<String, Queue> queues = queuesByName(declarables);

    @Test
    void eachChannelHasItsOwnWorkQueue() {
        assertThat(queues).containsKeys("notifications.email", "notifications.sms", "notifications.push");
    }

    @Test
    void eachChannelHasOneWaitQueuePerInterval() {
        assertThat(queues).containsKeys(
                "notifications.sms.wait.5000", "notifications.sms.wait.15000", "notifications.sms.wait.45000",
                "notifications.email.wait.5000", "notifications.push.wait.45000");
        assertThat(queues).hasSize(3 + 9 + 1);
    }

    @Test
    void aWaitQueueHoldsTheMessageForTheIntervalAndReturnsItToTheWorkQueue() {
        assertThat(queues.get("notifications.sms.wait.15000").getArguments())
                .containsEntry("x-message-ttl", 15_000)
                .containsEntry("x-dead-letter-exchange", "notifications")
                .containsEntry("x-dead-letter-routing-key", "notifications.sms");
    }

    @Test
    void aMessageRejectedByTheConsumerGoesToTheParkingLot() {
        assertThat(queues.get("notifications.email").getArguments())
                .containsEntry("x-dead-letter-exchange", "notifications")
                .containsEntry("x-dead-letter-routing-key", "notifications.parking-lot");
        assertThat(queues.get("notifications.parking-lot").getArguments()).isEmpty();
    }

    @Test
    void everyQueueIsDurableAndBoundByItsOwnName() {
        List<Binding> bindings = declarables.getDeclarablesByType(Binding.class);

        assertThat(queues.values()).allMatch(Queue::isDurable);
        assertThat(bindings).hasSameSizeAs(queues.values()).allSatisfy(binding -> {
            assertThat(binding.getExchange()).isEqualTo("notifications");
            assertThat(binding.getRoutingKey()).isEqualTo(binding.getDestination());
        });
    }

    @Test
    void equalIntervalsShareOneWaitQueue() {
        Map<String, Queue> withRepeatedInterval = queuesByName(declare(Duration.ofSeconds(2), Duration.ofSeconds(2)));

        assertThat(withRepeatedInterval).hasSize(3 + 3 + 1).containsKey("notifications.push.wait.2000");
    }

    private static Declarables declare(Duration... intervals) {
        return new RabbitTopology().notificationQueues(new RetryPolicy(List.of(intervals)));
    }

    private static Map<String, Queue> queuesByName(Declarables declarables) {
        return declarables.getDeclarablesByType(Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, Function.identity()));
    }
}
