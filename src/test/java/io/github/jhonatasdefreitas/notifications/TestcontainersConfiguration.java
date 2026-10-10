package io.github.jhonatasdefreitas.notifications;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    static class MailpitContainer extends GenericContainer<MailpitContainer> {

        private static final int SMTP_PORT = 1025;
        private static final int HTTP_PORT = 8025;

        MailpitContainer() {
            super("axllent/mailpit:v1.31");
            withExposedPorts(SMTP_PORT, HTTP_PORT);
            waitingFor(Wait.forHttp("/readyz").forPort(HTTP_PORT));
        }

        int smtpPort() {
            return getMappedPort(SMTP_PORT);
        }

        String httpUrl() {
            return "http://%s:%d".formatted(getHost(), getMappedPort(HTTP_PORT));
        }
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer("rabbitmq:4.3-management-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.2.2");
    }

    // Outside the tests this topic belongs to the order service. Three partitions, as in the contract.
    @Bean
    NewTopic orderEvents() {
        return TopicBuilder.name("orders.events").partitions(3).replicas(1).build();
    }

    @Bean
    MailpitContainer mailpit() {
        return new MailpitContainer();
    }

    // Spring Boot has no service connection for Mailpit, so the SMTP address is handed over by name.
    @Bean
    DynamicPropertyRegistrar mailProperties(MailpitContainer mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", mailpit::smtpPort);
        };
    }

    @Bean
    Mailbox mailbox(MailpitContainer mailpit) {
        return new Mailbox(mailpit.httpUrl());
    }
}
