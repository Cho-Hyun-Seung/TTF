package com.toki.ttf.contract.error;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Map;
import java.util.Objects;

@Getter
@Accessors(fluent = true)
public class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, String> fieldErrors;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.message(), null);
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ApiException(ErrorCode errorCode, Map<String, String> fieldErrors) {
        this(errorCode, errorCode.message(), fieldErrors);
    }

    public ApiException(ErrorCode errorCode, String message, Map<String, String> fieldErrors) {
        super(Objects.requireNonNull(message, "message"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.fieldErrors = fieldErrors == null || fieldErrors.isEmpty()
                ? null
                : Map.copyOf(fieldErrors);
    }

}
