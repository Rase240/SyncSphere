package com.syncsphere.web;

import com.syncsphere.document.DocumentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Turns exceptions thrown by REST controllers into RFC 7807 "problem detail" JSON responses, e.g.
 * <pre>{"type":"about:blank","title":"Not Found","status":404,"detail":"Document ... does not exist"}</pre>
 *
 * <p>This only applies to HTTP requests. STOMP errors are handled by the
 * {@code @MessageExceptionHandler} methods in CollaborationController.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} matters: Spring Boot registers its own ProblemDetail handler
 * (spring.mvc.problemdetails.enabled) for exceptions such as MethodArgumentNotValidException. Without the
 * order, Boot's generic handler would win and clients would not see the per-field {@code errors} list.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(DocumentNotFoundException.class)
    public ProblemDetail handleNotFound(DocumentNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid value for '" + ex.getName() + "': expected a UUID");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON");
    }

    /**
     * PostgreSQL or Redis down, connection pool exhausted, etc. The client may retry later.
     *
     * <p>CannotCreateTransactionException is listed separately because it is NOT a DataAccessException:
     * it is what {@code @Transactional} throws when it cannot even get a database connection to start the
     * transaction. (Found by stopping the postgres container: without it, the API returned 500.)
     */
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ProblemDetail handleDataStoreUnavailable(RuntimeException ex) {
        log.error("Data store unavailable while handling REST request", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "A data store is temporarily unavailable. Please retry.");
    }
}
