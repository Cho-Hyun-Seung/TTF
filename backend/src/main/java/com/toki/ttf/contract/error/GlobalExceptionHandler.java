package com.toki.ttf.contract.error;

import com.toki.ttf.contract.response.ApiResponse;
import com.toki.ttf.domain.common.DomainException;
import com.toki.ttf.domain.room.repository.RoomUnavailableException;
import com.toki.ttf.infrastructure.idempotency.IdempotencyService;
import com.toki.ttf.infrastructure.ratelimit.RateLimitService;
import com.toki.ttf.infrastructure.security.RequestSecurity;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Map<Throwable, ApiResponse<Void>> errorResponses =
            Collections.synchronizedMap(new WeakHashMap<>());

    @ExceptionHandler(ApiException.class)
    public ApiResponse<Void> handleApiException(
            ApiException exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                exception.errorCode(),
                exception.getMessage(),
                exception
        );
    }

    @ExceptionHandler(DomainException.class)
    public ApiResponse<Void> handleDomainException(
            DomainException exception,
            HttpServletResponse servletResponse
    ) {
        ErrorCode errorCode = domainErrorCode(exception.code());
        String message = errorCode == ErrorCode.INTERNAL_ERROR
                ? ErrorCode.INTERNAL_ERROR.message()
                : exception.getMessage();
        return response(servletResponse, errorCode, message, exception);
    }

    @ExceptionHandler(RoomUnavailableException.class)
    public ApiResponse<Void> handleRoomUnavailable(
            RoomUnavailableException exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                ErrorCode.ROOM_EXPIRED,
                ErrorCode.ROOM_EXPIRED.message(),
                exception
        );
    }

    @ExceptionHandler(IdempotencyService.InvalidIdempotencyKeyException.class)
    public ApiResponse<Void> handleInvalidIdempotencyKey(
            IdempotencyService.InvalidIdempotencyKeyException exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                ErrorCode.VALIDATION_ERROR,
                "Idempotency-Key를 확인해 주세요.",
                exception
        );
    }

    @ExceptionHandler(IdempotencyService.IdempotencyKeyReusedException.class)
    public ApiResponse<Void> handleIdempotencyKeyReused(
            IdempotencyService.IdempotencyKeyReusedException exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                ErrorCode.IDEMPOTENCY_KEY_REUSED,
                ErrorCode.IDEMPOTENCY_KEY_REUSED.message(),
                exception
        );
    }

    @ExceptionHandler(RateLimitService.RateLimitExceededException.class)
    public ApiResponse<Void> handleRateLimitExceeded(
            RateLimitService.RateLimitExceededException exception,
            HttpServletResponse servletResponse
    ) {
        servletResponse.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        return response(
                servletResponse,
                ErrorCode.RATE_LIMITED,
                ErrorCode.RATE_LIMITED.message(),
                exception
        );
    }

    @ExceptionHandler(RequestSecurity.UntrustedOriginException.class)
    public ApiResponse<Void> handleUntrustedOrigin(
            RequestSecurity.UntrustedOriginException exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                HttpStatus.FORBIDDEN,
                ErrorCode.SESSION_REQUIRED,
                "요청 출처를 확인할 수 없습니다. 다시 접속해 주세요.",
                exception
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.message(),
                exception
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.message(),
                exception
        );
    }

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            HttpMediaTypeNotSupportedException.class
    })
    public ApiResponse<Void> handleMalformedRequest(
            Exception exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.message(),
                exception
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(
            NoResourceFoundException exception,
            HttpServletResponse servletResponse
    ) {
        ErrorCode code = exception.getResourcePath().contains("/games/")
                ? ErrorCode.GAME_NOT_FOUND
                : ErrorCode.ROOM_NOT_FOUND;
        return response(servletResponse, code, code.message(), exception);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ApiResponse<Void> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                HttpStatus.METHOD_NOT_ALLOWED,
                ErrorCode.VALIDATION_ERROR,
                "지원하지 않는 요청 방식입니다.",
                exception
        );
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpectedException(
            Exception exception,
            HttpServletResponse servletResponse
    ) {
        return response(
                servletResponse,
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.message(),
                exception
        );
    }

    private ApiResponse<Void> response(
            HttpServletResponse servletResponse,
            ErrorCode errorCode,
            String message,
            Throwable source
    ) {
        return response(servletResponse, errorCode.httpStatus(), errorCode, message, source);
    }

    private ApiResponse<Void> response(
            HttpServletResponse servletResponse,
            HttpStatus status,
            ErrorCode errorCode,
            String message,
            Throwable source
    ) {
        servletResponse.setStatus(status.value());
        return errorResponses.computeIfAbsent(
                source,
                ignored -> ApiResponse.failure(errorCode.name(), message)
        );
    }

    private static ErrorCode domainErrorCode(DomainException.Code code) {
        try {
            return ErrorCode.valueOf(code.name());
        } catch (IllegalArgumentException exception) {
            return ErrorCode.INTERNAL_ERROR;
        }
    }
}
