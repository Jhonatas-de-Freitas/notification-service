package io.github.jhonatasdefreitas.notifications.domain;

import java.util.List;

public class InvalidRequestException extends RuntimeException {

    public record Violation(String field, String message) {
    }

    private final List<Violation> violations;

    public InvalidRequestException(List<Violation> violations) {
        super("Invalid request: " + violations);
        this.violations = List.copyOf(violations);
    }

    public InvalidRequestException(String field, String message) {
        this(List.of(new Violation(field, message)));
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
