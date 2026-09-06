package com.toki.ttf.infrastructure.security;

import com.toki.ttf.contract.error.ApiException;
import com.toki.ttf.contract.error.ErrorCode;
import com.toki.ttf.infrastructure.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class ApiRequestGuardInterceptor implements HandlerInterceptor {

    private static final int MAX_BURST_PER_SECOND = 200;
    private static final int MAX_SUSTAINED_PER_MINUTE = 2_000;

    private final RateLimitService rateLimitService;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > RequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "요청 본문이 너무 큽니다.");
        }

        String clientAddress = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        rateLimitService.check(
                "api-ip-burst",
                clientAddress,
                MAX_BURST_PER_SECOND,
                Duration.ofSeconds(1)
        );
        rateLimitService.check(
                "api-ip-sustained",
                clientAddress,
                MAX_SUSTAINED_PER_MINUTE,
                Duration.ofMinutes(1)
        );
        return true;
    }
}
