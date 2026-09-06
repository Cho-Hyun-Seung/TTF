package com.toki.ttf.infrastructure.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class SessionService {

    public static final String HOST_COOKIE = "ttf_host_session";
    public static final String PARTICIPANT_COOKIE = "ttf_participant_session";

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, HostSession> hostSessions = new ConcurrentHashMap<>();
    private final Map<String, ParticipantSession> participantSessions = new ConcurrentHashMap<>();
    @Value("${ttf.cookie.secure:true}")
    private final boolean secureCookies;

    public IssuedSession prepareHostSession(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            String existingKey = hash(rawToken);
            if (hostSessions.get(existingKey) != null) {
                return new IssuedSession(rawToken, existingKey);
            }
        }

        while (true) {
            String token = newToken();
            String key = hash(token);
            if (hostSessions.putIfAbsent(key, new HostSession()) == null) {
                return new IssuedSession(token, key);
            }
        }
    }

    public IssuedSession prepareParticipantSession(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            String existingKey = hash(rawToken);
            if (participantSessions.get(existingKey) != null) {
                return new IssuedSession(rawToken, existingKey);
            }
        }

        while (true) {
            String token = newToken();
            String key = hash(token);
            if (participantSessions.putIfAbsent(key, new ParticipantSession()) == null) {
                return new IssuedSession(token, key);
            }
        }
    }

    public void grantHost(String sessionKey, String roomId) {
        hostSessions.compute(sessionKey, (ignored, session) -> {
            HostSession target = session == null ? new HostSession() : session;
            target.roomIds.add(roomId);
            return target;
        });
    }

    public void grantParticipant(String sessionKey, String roomId, String participantId) {
        participantSessions.compute(sessionKey, (ignored, session) -> {
            ParticipantSession target = session == null ? new ParticipantSession() : session;
            target.participantByRoom.put(roomId, participantId);
            return target;
        });
    }

    public Optional<String> hostSessionKey(String rawToken, String roomId) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String key = hash(rawToken);
        HostSession session = hostSessions.get(key);
        if (session == null || !session.roomIds.contains(roomId)) {
            return Optional.empty();
        }
        return Optional.of(key);
    }

    public Optional<ParticipantGrant> participantGrant(String rawToken, String roomId) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String key = hash(rawToken);
        ParticipantSession session = participantSessions.get(key);
        if (session == null) {
            return Optional.empty();
        }
        String participantId = session.participantByRoom.get(roomId);
        return participantId == null
                ? Optional.empty()
                : Optional.of(new ParticipantGrant(key, participantId));
    }

    public boolean isKnownHostSession(String rawToken) {
        return knownKey(rawToken, hostSessions) != null;
    }

    public boolean isKnownParticipantSession(String rawToken) {
        return knownKey(rawToken, participantSessions) != null;
    }

    public Set<String> revokeParticipant(String roomId, String participantId) {
        Set<String> revokedSessionKeys = ConcurrentHashMap.newKeySet();
        participantSessions.forEach((key, ignored) -> participantSessions.computeIfPresent(key, (unused, session) -> {
            if (session.participantByRoom.remove(roomId, participantId)) {
                revokedSessionKeys.add(key);
            }
            return session.participantByRoom.isEmpty() ? null : session;
        }));
        return Set.copyOf(revokedSessionKeys);
    }

    public void revokeRoom(String roomId) {
        hostSessions.forEach((key, ignored) -> hostSessions.computeIfPresent(key, (unused, session) -> {
            session.roomIds.remove(roomId);
            return session.roomIds.isEmpty() ? null : session;
        }));
        participantSessions.forEach((key, ignored) -> participantSessions.computeIfPresent(key, (unused, session) -> {
            session.participantByRoom.remove(roomId);
            return session.participantByRoom.isEmpty() ? null : session;
        }));
    }

    /** Removes a newly prepared session when its request failed before any grant was made. */
    public void discardHostIfEmpty(String sessionKey) {
        hostSessions.computeIfPresent(sessionKey,
                (ignored, session) -> session.roomIds.isEmpty() ? null : session);
    }

    /** Removes a newly prepared session when its request failed before any grant was made. */
    public void discardParticipantIfEmpty(String sessionKey) {
        participantSessions.computeIfPresent(sessionKey,
                (ignored, session) -> session.participantByRoom.isEmpty() ? null : session);
    }

    public ResponseCookie hostCookie(String rawToken) {
        return cookie(HOST_COOKIE, rawToken);
    }

    public ResponseCookie participantCookie(String rawToken) {
        return cookie(PARTICIPANT_COOKIE, rawToken);
    }

    private ResponseCookie cookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path("/api/v1")
                .build();
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String knownKey(String rawToken, Map<String, ?> sessions) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String key = hash(rawToken);
        return sessions.containsKey(key) ? key : null;
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record IssuedSession(String rawToken, String sessionKey) {
    }

    public record ParticipantGrant(String sessionKey, String participantId) {
    }

    private static final class HostSession {
        private final Set<String> roomIds = ConcurrentHashMap.newKeySet();
    }

    private static final class ParticipantSession {
        private final Map<String, String> participantByRoom = new ConcurrentHashMap<>();
    }
}
