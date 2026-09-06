package com.toki.ttf.infrastructure.ratelimit;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RateLimitService {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong checks = new AtomicLong();
    private final Clock clock;
    private final ScheduledExecutorService cleanupExecutor;

    public RateLimitService() {
        this(Clock.systemUTC());
    }

    RateLimitService(Clock clock) {
        this.clock = clock;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ttf-rate-limit-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        cleanupExecutor.scheduleWithFixedDelay(this::removeExpiredWindows, 60, 60, TimeUnit.SECONDS);
    }

    public void check(String bucket, String subject, int limit, Duration duration) {
        long now = clock.millis();
        long durationMillis = duration.toMillis();
        String key = bucket + ':' + subject;
        Holder holder = new Holder();

        if ((checks.incrementAndGet() & 255) == 0) {
            removeExpiredWindows();
        }

        windows.compute(key, (ignored, current) -> {
            if (current == null || now >= current.endsAt) {
                return new Window(now + durationMillis, 1);
            }
            if (current.count >= limit) {
                holder.retryAfterSeconds = Math.max(1,
                        (current.endsAt - now + 999) / 1000);
                return current;
            }
            return new Window(current.endsAt, current.count + 1);
        });

        if (holder.retryAfterSeconds > 0) {
            throw new RateLimitExceededException(holder.retryAfterSeconds);
        }
    }

    private record Window(long endsAt, int count) {
    }

    private void removeExpiredWindows() {
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> entry.getValue().endsAt <= now);
    }

    @PreDestroy
    public void close() {
        cleanupExecutor.shutdownNow();
        windows.clear();
    }

    private static final class Holder {
        private long retryAfterSeconds;
    }

    @Getter
    @RequiredArgsConstructor
    @Accessors(fluent = true)
    public static final class RateLimitExceededException extends RuntimeException {
        private final long retryAfterSeconds;
    }
}
