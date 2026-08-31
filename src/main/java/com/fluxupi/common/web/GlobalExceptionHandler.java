package com.fluxupi.common.web;

import com.fluxupi.common.FluxUpiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Turns exceptions into RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Every deliberate domain error extends {@link FluxUpiException} and carries
 * its own HTTP status and a stable {@code errorCode}, so this handler never has
 * to pattern-match on exception type to decide what to return.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FluxUpiException.class)
    public ProblemDetail handleDomain(FluxUpiException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getErrorCode());
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("path", request.getRequestURI());
        if (ex.getStatus().is5xxServerError()) {
            log.error("Domain error {} on {}", ex.getErrorCode(), request.getRequestURI(), ex);
        } else {
            log.debug("Domain error {} on {}: {}", ex.getErrorCode(), request.getRequestURI(), ex.getMessage());
        }
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("request validation failed");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("VALIDATION_ERROR");
        problem.setProperty("errorCode", "VALIDATION_ERROR");
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("BAD_REQUEST");
        problem.setType(URI.create("about:blank"));
        problem.setProperty("errorCode", "BAD_REQUEST");
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }
}
