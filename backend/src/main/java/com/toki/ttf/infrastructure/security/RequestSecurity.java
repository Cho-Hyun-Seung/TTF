package com.toki.ttf.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RequestSecurity {

    private final Set<String> allowedOrigins;

    public RequestSecurity(@Value("${ttf.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void requireTrustedMutation(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            if (allowedOrigins.contains(origin) || isSameOrigin(origin, request)) {
                return;
            }
            throw new UntrustedOriginException();
        }
        if ("same-origin".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            return;
        }
        throw new UntrustedOriginException();
    }

    private static boolean isSameOrigin(String origin, HttpServletRequest request) {
        try {
            URI uri = new URI(origin);
            int originPort = uri.getPort() >= 0 ? uri.getPort() : defaultPort(uri.getScheme());
            int requestPort = request.getServerPort();
            return uri.getScheme() != null
                    && uri.getScheme().equalsIgnoreCase(request.getScheme())
                    && uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(request.getServerName())
                    && originPort == requestPort;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    public static final class UntrustedOriginException extends RuntimeException {
    }
}
