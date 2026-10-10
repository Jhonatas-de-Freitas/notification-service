package io.github.jhonatasdefreitas.notifications.events;

/** A message that will never be readable, no matter how many times it is retried. */
class InvalidEventException extends RuntimeException {

    InvalidEventException(String message) {
        super(message);
    }

    InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
