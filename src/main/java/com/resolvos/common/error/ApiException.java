package com.resolvos.common.error;

import org.springframework.http.HttpStatus;
import java.util.List;

/**
 * Application-level exception mapped to ProblemDetail by GlobalExceptionHandler.
 * Use subclasses (NotFoundException, ConflictException, etc.) — never throw this directly.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> errors;

    public ApiException(HttpStatus status, String errorCode, String message, List<String> errors) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.errors = errors == null ? List.of() : errors;
    }

    public ApiException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, List.of());
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public List<String> getErrors() { return errors; }

    // ─── Convenience factories ────────────────────────────────────────────────

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "conflict", message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "business-rule", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad-request", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }
}
