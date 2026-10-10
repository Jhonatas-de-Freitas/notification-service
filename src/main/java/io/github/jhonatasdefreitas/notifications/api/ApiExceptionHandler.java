package io.github.jhonatasdefreitas.notifications.api;

import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.jhonatasdefreitas.notifications.domain.InvalidRequestException;
import io.github.jhonatasdefreitas.notifications.domain.InvalidRequestException.Violation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/** Every error leaves as application/problem+json. The base class already covers the ones Spring raises. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final URI NO_SPECIFIC_TYPE = URI.create("about:blank");

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(Violation::field))
                .toList();
        return handleExceptionInternal(ex, invalidFields(errors), headers, status, request);
    }

    // A value that does not fit its field, such as a channel that does not exist, fails while the
    // JSON is read, before the validation. It still names a field, so it is reported like the others.
    // Text that is not JSON at all names none and keeps the answer Spring gives.
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            Violation error = new Violation(fieldAt(mismatch.getPath()), expectedFor(mismatch.getTargetType()));
            return handleExceptionInternal(ex, invalidFields(List.of(error)), headers, status, request);
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException parameter) {
            Violation error = new Violation(parameter.getName(), expectedFor(parameter.getRequiredType()));
            return handleExceptionInternal(ex, invalidFields(List.of(error)), headers, status, request);
        }
        return super.handleTypeMismatch(ex, headers, status, request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        return invalidFields(ex.getViolations());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error, try again later");
    }

    // Spring leaves "type" out when there is no specific one. The contract of the store lists the
    // field, so it always goes, with the value the RFC gives for that case.
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getType() == null) {
            problem.setType(NO_SPECIFIC_TYPE);
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private static String fieldAt(List<JacksonException.Reference> path) {
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference step : path) {
            if (step.getPropertyName() == null) {
                field.append('[').append(step.getIndex()).append(']');
            } else {
                field.append(field.isEmpty() ? "" : ".").append(step.getPropertyName());
            }
        }
        return field.toString();
    }

    private static String expectedFor(Class<?> type) {
        if (type == null || !type.isEnum()) {
            return "has an invalid format";
        }
        return Arrays.stream(type.getEnumConstants()).map(String::valueOf)
                .collect(Collectors.joining(", ", "must be one of ", ""));
    }

    private static ProblemDetail invalidFields(List<Violation> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "The request has invalid fields");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(NO_SPECIFIC_TYPE);
        return problem;
    }
}
