package io.github.jhonatasdefreitas.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StartupIT extends IntegrationTest {

    @Test
    void healthIsUpWithDatabaseBrokerAndMailServer() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void exposesDeliveryMetricsToPrometheus() {
        awaitStatus(sendOne(generic(newUser(), emailRecipient(newEmail()), "EMAIL")), "SENT");

        client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("jvm_memory_used_bytes")
                        .contains("notifications_deliveries_total{channel=\"EMAIL\",result=\"sent\"}")
                        .contains("notifications_delivery_time_seconds_count{channel=\"EMAIL\"}")
                        .contains("notifications_parking_lot_messages"));
    }

    @Test
    void documentsTheApi() {
        client.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths['/notifications'].post").exists()
                .jsonPath("$.paths['/notifications/{id}'].get").exists()
                .jsonPath("$.paths['/preferences/{userId}'].put").exists();
    }
}
