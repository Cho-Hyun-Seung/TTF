package com.toki.ttf.infrastructure.sse;

import com.toki.ttf.domain.ttf.dto.response.GameEventResponse;
import com.toki.ttf.infrastructure.ratelimit.RateLimitService.RateLimitExceededException;
import jakarta.annotation.PreDestroy;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class GameEventHub {

    private static final int HISTORY_LIMIT = 256;
    private static final int MAX_PENDING_PER_CONNECTION = 512;
    private static final int MAX_CONNECTIONS_PER_SUBJECT_PER_GAME = 2;
    private static final int MAX_CONNECTIONS_PER_SUBJECT_TOTAL = 8;
    private static final int MAX_CONNECTIONS_PER_CLIENT_ADDRESS = 256;
    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final Duration RETIRED_STREAM_TTL = Duration.ofMinutes(30);
    private static final long DISCONNECT_GRACE_SECONDS = 5;

    private final Map<String, GameStream> streams = new ConcurrentHashMap<>();
    private final Map<String, Instant> retiredGames = new ConcurrentHashMap<>();
    private final Map<String, Integer> connectionsBySubject = new ConcurrentHashMap<>();
    private final Map<String, Integer> connectionsByClientAddress = new ConcurrentHashMap<>();
    private final Object connectionLock = new Object();
    private final ExecutorService deliveryExecutor = new ThreadPoolExecutor(
            8,
            256,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            task -> {
                Thread thread = new Thread(task, "ttf-sse-delivery");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ttf-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public GameEventHub() {
        heartbeatExecutor.scheduleAtFixedRate(this::heartbeat, 20, 20, TimeUnit.SECONDS);
    }

    public void publish(String eventName, String roomId, String gameId, long version) {
        if (isRetired(gameId)) {
            return;
        }

        /* ignored는 사용되지 않는 값이라 ignored라고 입력 */
        GameStream stream = streams.computeIfAbsent(gameId, ignored -> new GameStream());
        synchronized (stream) {
            if (stream.closed || isRetired(gameId)) {
                streams.remove(gameId, stream);
                return;
            }
            long sequence = ++stream.sequence;
            GameEventResponse payload = eventPayload(roomId, gameId, version);
            StoredEvent event = new StoredEvent(sequence, eventName, payload);
            stream.history.addLast(event);
            while (stream.history.size() > HISTORY_LIMIT) {
                stream.history.removeFirst();
            }
            stream.roomId = roomId;
            stream.version = Math.max(stream.version, version);
            for (Subscriber subscriber : new ArrayList<>(stream.subscribers)) {
                enqueue(stream, subscriber, Outbound.event(event));
            }
        }
    }

    public SseEmitter subscribe(
            String roomId,
            String gameId,
            long currentVersion,
            String subject,
            String clientAddress,
            String lastEventId,
            Runnable onConnected,
            Runnable onDisconnected
    ) {
        if (isRetired(gameId)) {
            throw new GameStreamClosedException();
        }
        GameStream stream = streams.computeIfAbsent(gameId, ignored -> new GameStream());
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        Subscriber subscriber = new Subscriber(subject, clientAddress, emitter, onDisconnected);
        boolean notifyConnected;

        synchronized (stream) {
            if (stream.closed || isRetired(gameId)) {
                streams.remove(gameId, stream);
                throw new GameStreamClosedException();
            }
            long connectionCount = stream.subscribers.stream()
                    .filter(existing -> existing.subject.equals(subject))
                    .count();
            if (connectionCount >= MAX_CONNECTIONS_PER_SUBJECT_PER_GAME) {
                throw new RateLimitExceededException(3);
            }
            reserveConnection(subject, clientAddress);
            PendingDisconnect pendingDisconnect = stream.pendingDisconnects.remove(subject);
            if (pendingDisconnect != null) {
                pendingDisconnect.future.cancel(false);
            }
            notifyConnected = connectionCount == 0 && pendingDisconnect == null;

            List<Outbound> initial = new ArrayList<>();
            initial.add(Outbound.comment("connected"));
            Long lastSequence = parseSequence(lastEventId);
            boolean hasLastEventId = lastEventId != null && !lastEventId.isBlank();
            boolean syncRequired = false;
            if (lastSequence != null) {
                if (lastSequence < 0 || lastSequence > stream.sequence) {
                    syncRequired = true;
                } else if (!stream.history.isEmpty()) {
                    long oldest = stream.history.getFirst().sequence;
                    if (lastSequence < oldest - 1) {
                        syncRequired = true;
                    } else {
                        stream.history.stream()
                                .filter(event -> event.sequence > lastSequence)
                                .map(Outbound::event)
                                .forEach(initial::add);
                    }
                }
            } else if (hasLastEventId) {
                syncRequired = true;
            }

            stream.roomId = roomId;
            stream.version = Math.max(stream.version, currentVersion);
            if (syncRequired) {
                initial.add(Outbound.event(new StoredEvent(
                        stream.sequence,
                        "game.sync_required",
                        eventPayload(roomId, gameId, stream.version)
                )));
            }

            synchronized (subscriber) {
                subscriber.pending.addAll(initial);
                subscriber.draining = true;
                subscriber.counted = true;
            }
            stream.subscribers.add(subscriber);
        }

        Runnable cleanup = () -> disconnect(stream, subscriber, false);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        startDrain(stream, subscriber);
        if (notifyConnected) {
            runCallback(onConnected);
        }
        return emitter;
    }

    public void removeGame(String gameId) {
        retiredGames.put(gameId, Instant.now().plus(RETIRED_STREAM_TTL));
        GameStream stream = streams.remove(gameId);
        if (stream == null) {
            return;
        }
        List<Subscriber> subscribers;
        synchronized (stream) {
            stream.closed = true;
            subscribers = new ArrayList<>(stream.subscribers);
            stream.subscribers.clear();
            stream.history.clear();
            stream.pendingDisconnects.values().forEach(pending -> pending.future.cancel(false));
            stream.pendingDisconnects.clear();
        }
        subscribers.forEach(subscriber -> closeDetachedSubscriber(subscriber, true));
    }

    public void removeSubject(String gameId, String subject) {
        GameStream stream = streams.get(gameId);
        if (stream == null) {
            return;
        }
        List<Subscriber> subscribers;
        synchronized (stream) {
            subscribers = stream.subscribers.stream()
                    .filter(subscriber -> subscriber.subject.equals(subject))
                    .toList();
            stream.subscribers.removeAll(subscribers);
            PendingDisconnect pendingDisconnect = stream.pendingDisconnects.remove(subject);
            if (pendingDisconnect != null && pendingDisconnect.future != null) {
                pendingDisconnect.future.cancel(false);
            }
        }
        subscribers.forEach(subscriber -> closeDetachedSubscriber(subscriber, true));
    }

    public static String participantSubject(String sessionKey) {
        return "participant:" + sessionKey;
    }

    public boolean hasConnections(String gameId, String subject) {
        GameStream stream = streams.get(gameId);
        if (stream == null) {
            return false;
        }
        synchronized (stream) {
            return !stream.closed && stream.subscribers.stream()
                    .anyMatch(subscriber -> subscriber.subject.equals(subject));
        }
    }

    private void enqueue(GameStream stream, Subscriber subscriber, Outbound outbound) {
        boolean start = false;
        boolean overflow = false;
        synchronized (subscriber) {
            if (subscriber.closed) {
                return;
            }
            if (subscriber.pending.size() >= MAX_PENDING_PER_CONNECTION) {
                overflow = true;
            } else {
                subscriber.pending.addLast(outbound);
                if (!subscriber.draining) {
                    subscriber.draining = true;
                    start = true;
                }
            }
        }
        if (overflow) {
            disconnect(stream, subscriber, true);
        } else if (start) {
            startDrain(stream, subscriber);
        }
    }

    private void startDrain(GameStream stream, Subscriber subscriber) {
        try {
            deliveryExecutor.execute(() -> drain(stream, subscriber));
        } catch (RejectedExecutionException exception) {
            disconnect(stream, subscriber, true);
        }
    }

    private void drain(GameStream stream, Subscriber subscriber) {
        while (true) {
            Outbound outbound;
            synchronized (subscriber) {
                if (subscriber.closed) {
                    return;
                }
                outbound = subscriber.pending.pollFirst();
                if (outbound == null) {
                    subscriber.draining = false;
                    return;
                }
            }
            try {
                if (outbound.comment != null) {
                    subscriber.emitter.send(SseEmitter.event().comment(outbound.comment).reconnectTime(3000));
                } else {
                    StoredEvent event = outbound.event;
                    subscriber.emitter.send(SseEmitter.event()
                            .id(Long.toString(event.sequence))
                            .name(event.name)
                            .reconnectTime(3000)
                            .data(event.payload));
                }
            } catch (IOException | IllegalStateException exception) {
                disconnect(stream, subscriber, true);
                return;
            }
        }
    }

    private void heartbeat() {
        Instant now = Instant.now();
        retiredGames.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        streams.values().forEach(stream -> {
            synchronized (stream) {
                if (!stream.closed) {
                    for (Subscriber subscriber : new ArrayList<>(stream.subscribers)) {
                        enqueue(stream, subscriber, Outbound.comment("heartbeat"));
                    }
                }
            }
        });
    }

    private void disconnect(GameStream stream, Subscriber subscriber, boolean complete) {
        boolean removed;
        boolean wasOpen;
        synchronized (stream) {
            removed = stream.subscribers.remove(subscriber);
            wasOpen = markClosed(subscriber);
            if (removed
                    && !stream.closed
                    && subscriber.onDisconnected != null
                    && stream.subscribers.stream().noneMatch(
                    existing -> existing.subject.equals(subscriber.subject))) {
                scheduleDisconnect(stream, subscriber.subject, subscriber.onDisconnected);
            }
        }
        if (wasOpen) {
            releaseConnection(subscriber);
        }
        if (complete) {
            completeQuietly(subscriber.emitter);
        }
    }

    private void closeDetachedSubscriber(Subscriber subscriber, boolean complete) {
        boolean wasOpen = markClosed(subscriber);
        if (wasOpen) {
            releaseConnection(subscriber);
        }
        if (complete) {
            completeQuietly(subscriber.emitter);
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // The servlet container may already have completed the async response.
        }
    }

    private void scheduleDisconnect(GameStream stream, String subject, Runnable callback) {
        PendingDisconnect pending = new PendingDisconnect(callback);
        stream.pendingDisconnects.put(subject, pending);
        try {
            pending.future = heartbeatExecutor.schedule(
                    () -> completeDisconnect(stream, subject, pending),
                    DISCONNECT_GRACE_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (RejectedExecutionException exception) {
            stream.pendingDisconnects.remove(subject, pending);
        }
    }

    private void completeDisconnect(GameStream stream, String subject, PendingDisconnect pending) {
        synchronized (stream) {
            if (stream.pendingDisconnects.get(subject) != pending
                    || stream.subscribers.stream().anyMatch(existing -> existing.subject.equals(subject))) {
                return;
            }
            stream.pendingDisconnects.remove(subject);
        }
        runCallback(pending.callback);
    }

    private static void runCallback(Runnable callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Connection bookkeeping must not terminate event delivery threads.
        }
    }

    private static boolean markClosed(Subscriber subscriber) {
        synchronized (subscriber) {
            if (subscriber.closed) {
                return false;
            }
            subscriber.closed = true;
            subscriber.pending.clear();
            return true;
        }
    }

    private void reserveConnection(String subject, String clientAddress) {
        synchronized (connectionLock) {
            int subjectCount = connectionsBySubject.getOrDefault(subject, 0);
            int addressCount = connectionsByClientAddress.getOrDefault(clientAddress, 0);
            if (subjectCount >= MAX_CONNECTIONS_PER_SUBJECT_TOTAL
                    || addressCount >= MAX_CONNECTIONS_PER_CLIENT_ADDRESS) {
                throw new RateLimitExceededException(3);
            }
            connectionsBySubject.put(subject, subjectCount + 1);
            connectionsByClientAddress.put(clientAddress, addressCount + 1);
        }
    }

    private void releaseConnection(Subscriber subscriber) {
        synchronized (subscriber) {
            if (!subscriber.counted) {
                return;
            }
            subscriber.counted = false;
        }
        synchronized (connectionLock) {
            connectionsBySubject.computeIfPresent(
                    subscriber.subject,
                    (ignored, count) -> count <= 1 ? null : count - 1
            );
            connectionsByClientAddress.computeIfPresent(
                    subscriber.clientAddress,
                    (ignored, count) -> count <= 1 ? null : count - 1
            );
        }
    }

    private boolean isRetired(String gameId) {
        Instant until = retiredGames.get(gameId);
        if (until == null) {
            return false;
        }
        if (until.isAfter(Instant.now())) {
            return true;
        }
        retiredGames.remove(gameId, until);
        return false;
    }

    private static GameEventResponse eventPayload(String roomId, String gameId, long version) {
        return new GameEventResponse(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                roomId,
                gameId,
                version,
                Instant.now()
        );
    }

    private static Long parseSequence(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @PreDestroy
    public void close() {
        heartbeatExecutor.shutdownNow();
        deliveryExecutor.shutdownNow();
        new ArrayList<>(streams.keySet()).forEach(this::removeGame);
        connectionsBySubject.clear();
        connectionsByClientAddress.clear();
    }

    public static final class GameStreamClosedException extends RuntimeException {
    }

    private static final class GameStream {
        private final Deque<StoredEvent> history = new ArrayDeque<>();
        private final List<Subscriber> subscribers = new ArrayList<>();
        private final Map<String, PendingDisconnect> pendingDisconnects = new ConcurrentHashMap<>();
        private long sequence;
        private long version;
        private String roomId;
        private boolean closed;

    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Subscriber {
        private final String subject;
        private final String clientAddress;
        private final SseEmitter emitter;
        private final Runnable onDisconnected;
        private final Deque<Outbound> pending = new ArrayDeque<>();
        private boolean draining;
        private boolean closed;
        private boolean counted;

    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class PendingDisconnect {
        private final Runnable callback;
        private volatile ScheduledFuture<?> future;
    }

    private record Outbound(String comment, StoredEvent event) {
        private static Outbound comment(String value) {
            return new Outbound(value, null);
        }

        private static Outbound event(StoredEvent value) {
            return new Outbound(null, value);
        }
    }

    private record StoredEvent(long sequence, String name, GameEventResponse payload) {
    }
}
