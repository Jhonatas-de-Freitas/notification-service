package io.github.jhonatasdefreitas.notifications.messaging;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
class ParkingLotGauge {

    ParkingLotGauge(MeterRegistry registry, AmqpAdmin admin) {
        Gauge.builder("notifications.parking.lot.messages", admin, ParkingLotGauge::messageCount)
                .description("Messages in the parking lot queue")
                .register(registry);
    }

    private static double messageCount(AmqpAdmin admin) {
        QueueInformation queue = admin.getQueueInfo(RabbitTopology.PARKING_LOT);
        return queue == null ? 0 : queue.getMessageCount();
    }
}
