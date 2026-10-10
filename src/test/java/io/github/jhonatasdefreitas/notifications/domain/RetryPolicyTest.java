package io.github.jhonatasdefreitas.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45)));

    @Test
    void threeIntervalsAllowFourAttempts() {
        assertThat(policy.maxAttempts()).isEqualTo(4);
    }

    @Test
    void waitsLongerAfterEachFailure() {
        assertThat(policy.delayAfter(1)).contains(Duration.ofSeconds(5));
        assertThat(policy.delayAfter(2)).contains(Duration.ofSeconds(15));
        assertThat(policy.delayAfter(3)).contains(Duration.ofSeconds(45));
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        assertThat(policy.delayAfter(4)).isEmpty();
    }

    @Test
    void withoutIntervalsThereIsASingleAttempt() {
        RetryPolicy noRetries = new RetryPolicy(List.of());

        assertThat(noRetries.maxAttempts()).isEqualTo(1);
        assertThat(noRetries.delayAfter(1)).isEmpty();
    }

    @Test
    void refusesAnIntervalThatIsNotPositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(List.of(Duration.ZERO)));
    }
}
