package io.github.jhonatasdefreitas.notifications;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.jhonatasdefreitas.notifications.domain.RetryPolicy;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ServiceConfiguration.RetryProperties.class)
class ServiceConfiguration {

    @ConfigurationProperties("notifications.retry")
    record RetryProperties(List<Duration> intervals) {
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RetryPolicy retryPolicy(RetryProperties properties) {
        return new RetryPolicy(properties.intervals());
    }
}
