package com.toki.ttf.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBodyLimitFilterTests {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter();

    @Test
    void rejectsActualBodyOverLimitWhenContentLengthIsMissing() {
        HttpServletRequest request = requestWithDeclaredLength(
                oversizedBody(),
                -1
        );
        AtomicInteger controllerCalls = new AtomicInteger();

        assertThatThrownBy(() -> filter.doFilter(
                request,
                new MockHttpServletResponse(),
                consumingChain(controllerCalls)
        ))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("request body exceeds the configured limit");
        assertThat(controllerCalls).hasValue(0);
    }

    @Test
    void rejectsActualBodyOverLimitWhenContentLengthIsFalselyLow() {
        HttpServletRequest request = requestWithDeclaredLength(
                oversizedBody(),
                1
        );
        AtomicInteger controllerCalls = new AtomicInteger();

        assertThatThrownBy(() -> filter.doFilter(
                request,
                new MockHttpServletResponse(),
                consumingChain(controllerCalls)
        ))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("request body exceeds the configured limit");
        assertThat(controllerCalls).hasValue(0);
    }

    @Test
    void allowsBodyExactlyAtLimit() throws Exception {
        byte[] body = new byte[(int) RequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES];
        HttpServletRequest request = requestWithDeclaredLength(body, -1);
        AtomicInteger consumedBytes = new AtomicInteger();

        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) ->
                consumedBytes.set(wrapped.getInputStream().readAllBytes().length));

        assertThat(consumedBytes).hasValue(body.length);
    }

    private static FilterChain consumingChain(AtomicInteger controllerCalls) {
        return (request, response) -> {
            request.getInputStream().transferTo(OutputStream.nullOutputStream());
            controllerCalls.incrementAndGet();
        };
    }

    private static byte[] oversizedBody() {
        return new byte[(int) RequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES + 1];
    }

    private static HttpServletRequest requestWithDeclaredLength(byte[] body, long declaredLength) {
        MockHttpServletRequest delegate = new MockHttpServletRequest("POST", "/api/v1/probe");
        delegate.setContentType("application/json");
        delegate.setContent(body);
        return new HttpServletRequestWrapper(delegate) {
            @Override
            public int getContentLength() {
                return (int) declaredLength;
            }

            @Override
            public long getContentLengthLong() {
                return declaredLength;
            }
        };
    }
}
