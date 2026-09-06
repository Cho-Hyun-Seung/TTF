package com.toki.ttf.infrastructure.idempotency;

import com.toki.ttf.contract.error.ApiException;
import com.toki.ttf.domain.common.DomainException;
import com.toki.ttf.domain.room.repository.RoomUnavailableException;
import com.toki.ttf.infrastructure.ratelimit.RateLimitService.RateLimitExceededException;
import jakarta.annotation.PreDestroy;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class IdempotencyService {

    private static final int MAX_KEY_LENGTH = 200;
    private static final int MAX_RECORDS_PER_SESSION = 2_000;
    private static final int MAX_RECORDS_TOTAL = 100_000;
    private static final int MAX_FAILURE_RECORDS_PER_SESSION = 128;
    private static final int MAX_FAILURE_RECORDS_TOTAL = 10_000;
    private static final long RETIRED_RESOURCE_TTL_MILLIS = 30 * 60 * 1000L;

    private final Map<OperationKey, OperationEntry> results = new ConcurrentHashMap<>();
    private final Map<String, Long> retiredResources = new ConcurrentHashMap<>();
    private final Map<String, Long> retiredResponseResources = new ConcurrentHashMap<>();
    private final Map<String, Integer> recordsBySession = new ConcurrentHashMap<>();
    private final Map<String, Integer> failureRecordsBySession = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ttf-idempotency-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private int failureRecordCount;

    public IdempotencyService() {
        cleanupExecutor.scheduleWithFixedDelay(
                this::pruneRetiredResources,
                60,
                60,
                TimeUnit.SECONDS
        );
    }

    public <T> Result<T> execute(
            String sessionScope,
            String endpoint,
            String idempotencyKey,
            Object requestBody,
            Supplier<T> operation
    ) {
        return execute(sessionScope, endpoint, idempotencyKey, requestBody, operation, true, true);
    }

    public <T> Result<T> executeWithoutFailureReplay(
            String sessionScope,
            String endpoint,
            String idempotencyKey,
            Object requestBody,
            Supplier<T> operation
    ) {
        return execute(sessionScope, endpoint, idempotencyKey, requestBody, operation, false, true);
    }

    public <T> Result<T> executeDiscardingFalseResult(
            String sessionScope,
            String endpoint,
            String idempotencyKey,
            Object requestBody,
            Supplier<T> operation
    ) {
        return execute(sessionScope, endpoint, idempotencyKey, requestBody, operation, true, false);
    }

    private <T> Result<T> execute(
            String sessionScope,
            String endpoint,
            String idempotencyKey,
            Object requestBody,
            Supplier<T> operation,
            boolean replayFailures,
            boolean retainFalseResult
    ) {
        validateKey(idempotencyKey);
        pruneRetiredResources();
        if (endpointReferencesRetiredResource(endpoint)) {
            throw new RoomUnavailableException();
        }

        OperationKey key = new OperationKey(sessionScope, endpoint, idempotencyKey);
        String requestFingerprint = fingerprint(requestBody);
        OperationEntry candidate = new OperationEntry(requestFingerprint);
        Registration registration = register(key, candidate);
        if (!registration.owner) {
            return replay(registration.entry, requestFingerprint, key);
        }
        return executeOwner(
                key,
                candidate,
                endpoint,
                operation,
                replayFailures,
                retainFalseResult
        );
    }

    private Registration register(OperationKey key, OperationEntry candidate) {
        synchronized (admissionLock) {
            OperationEntry existing = results.get(key);
            if (existing != null) {
                return new Registration(existing, false);
            }
            if (results.size() >= MAX_RECORDS_TOTAL) {
                throw new RateLimitExceededException(60);
            }
            int current = recordsBySession.getOrDefault(key.sessionScope, 0);
            if (current >= MAX_RECORDS_PER_SESSION) {
                throw new RateLimitExceededException(60);
            }
            results.put(key, candidate);
            recordsBySession.put(key.sessionScope, current + 1);
            return new Registration(candidate, true);
        }
    }

    private <T> Result<T> executeOwner(
            OperationKey key,
            OperationEntry entry,
            String endpoint,
            Supplier<T> operation,
            boolean replayFailures,
            boolean retainFalseResult
    ) {
        StoredResult prepared;
        boolean retainFailure = false;
        try {
            T response = operation.get();
            prepared = new StoredResult(response, null);
        } catch (RuntimeException exception) {
            retainFailure = replayFailures
                    && isCompactReplayableFailure(exception)
                    && !(exception instanceof RateLimitExceededException)
                    && !(exception instanceof RoomUnavailableException)
                    && !endpointReferencesRetiredResource(endpoint);
            prepared = new StoredResult(null, exception);
            entry.prepared = prepared;
            if (!retainFailure) {
                removeEntry(key, entry);
                if (!entry.completion.complete(prepared)) {
                    return resultFromStored(entry.completion.join(), false);
                }
                return resultFromStored(prepared, false);
            }
        } catch (Error error) {
            removeEntry(key, entry);
            entry.completion.completeExceptionally(error);
            throw error;
        }

        entry.prepared = prepared;
        if (endpointReferencesRetiredResource(endpoint)
                || responseReferencesRetiredResource(prepared.response)) {
            RoomUnavailableException unavailable = new RoomUnavailableException();
            retireEntry(key, entry, unavailable);
            throw unavailable;
        }

        if (retainFailure && !reserveFailureRecord(key, entry)) {
            removeEntry(key, entry);
            if (!entry.completion.complete(prepared)) {
                return resultFromStored(entry.completion.join(), false);
            }
            return resultFromStored(prepared, false);
        }

        if (retainFailure) {
            prepared.failure.setStackTrace(new StackTraceElement[0]);
        }

        if (!retainFalseResult && Boolean.FALSE.equals(prepared.response)) {
            removeEntry(key, entry);
        }

        if (!entry.completion.complete(prepared)) {
            return resultFromStored(entry.completion.join(), false);
        }
        return resultFromStored(prepared, false);
    }

    private <T> Result<T> replay(
            OperationEntry entry,
            String requestFingerprint,
            OperationKey key
    ) {
        if (!entry.requestFingerprint.equals(requestFingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        if (endpointReferencesRetiredResource(key.endpoint)) {
            RoomUnavailableException unavailable = new RoomUnavailableException();
            retireEntry(key, entry, unavailable);
            throw unavailable;
        }
        StoredResult stored = entry.completion.join();
        if (endpointReferencesRetiredResource(key.endpoint)
                || responseReferencesRetiredResource(stored.response)) {
            RoomUnavailableException unavailable = new RoomUnavailableException();
            retireEntry(key, entry, unavailable);
            throw unavailable;
        }
        return resultFromStored(stored, true);
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> resultFromStored(StoredResult stored, boolean replayed) {
        if (stored.failure != null) {
            throw stored.failure;
        }
        return new Result<>((T) stored.response, replayed);
    }

    public void removeForResource(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        pruneRetiredResources();
        retiredResources.put(resourceId, System.currentTimeMillis() + RETIRED_RESOURCE_TTL_MILLIS);
        for (Map.Entry<OperationKey, OperationEntry> result : results.entrySet()) {
            OperationEntry entry = result.getValue();
            StoredResult prepared = entry.prepared;
            if (result.getKey().endpoint.contains(resourceId)
                    || (prepared != null && responseReferences(prepared.response, resourceId))) {
                retireEntry(result.getKey(), entry, new RoomUnavailableException());
            }
        }
    }

    public void removeResponsesForResource(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        pruneRetiredResources();
        retiredResponseResources.put(
                resourceId,
                System.currentTimeMillis() + RETIRED_RESOURCE_TTL_MILLIS
        );
        for (Map.Entry<OperationKey, OperationEntry> result : results.entrySet()) {
            StoredResult prepared = result.getValue().prepared;
            if (prepared != null
                    && responseReferences(prepared.response, resourceId)) {
                retireEntry(
                        result.getKey(),
                        result.getValue(),
                        new RoomUnavailableException()
                );
            }
        }
    }

    private void retireEntry(
            OperationKey key,
            OperationEntry entry,
            RoomUnavailableException unavailable
    ) {
        if (removeEntry(key, entry)) {
            StoredResult retired = new StoredResult(null, unavailable);
            entry.prepared = retired;
            entry.completion.complete(retired);
        }
    }

    private boolean removeEntry(OperationKey key, OperationEntry entry) {
        synchronized (admissionLock) {
            if (!results.remove(key, entry)) {
                return false;
            }
            recordsBySession.computeIfPresent(
                    key.sessionScope,
                    (ignored, count) -> count <= 1 ? null : count - 1
            );
            if (entry.retainedFailure) {
                failureRecordCount--;
                failureRecordsBySession.computeIfPresent(
                        key.sessionScope,
                        (ignored, count) -> count <= 1 ? null : count - 1
                );
            }
            return true;
        }
    }

    private boolean reserveFailureRecord(OperationKey key, OperationEntry entry) {
        synchronized (admissionLock) {
            if (results.get(key) != entry) {
                return false;
            }
            int sessionFailures = failureRecordsBySession.getOrDefault(key.sessionScope, 0);
            if (failureRecordCount >= MAX_FAILURE_RECORDS_TOTAL
                    || sessionFailures >= MAX_FAILURE_RECORDS_PER_SESSION) {
                return false;
            }
            entry.retainedFailure = true;
            failureRecordCount++;
            failureRecordsBySession.put(key.sessionScope, sessionFailures + 1);
            return true;
        }
    }

    private static boolean isCompactReplayableFailure(RuntimeException exception) {
        return exception instanceof ApiException || exception instanceof DomainException;
    }

    private static boolean responseReferences(Object response, String resourceId) {
        return response != null && response.toString().contains(resourceId);
    }

    private boolean endpointReferencesRetiredResource(String endpoint) {
        return retiredResources.keySet().stream().anyMatch(endpoint::contains);
    }

    private boolean responseReferencesRetiredResource(Object response) {
        return response != null
                && (retiredResources.keySet().stream()
                .anyMatch(resourceId -> responseReferences(response, resourceId))
                || retiredResponseResources.keySet().stream()
                .anyMatch(resourceId -> responseReferences(response, resourceId)));
    }

    private void pruneRetiredResources() {
        long now = System.currentTimeMillis();
        retiredResources.entrySet().removeIf(entry -> entry.getValue() <= now);
        retiredResponseResources.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    @PreDestroy
    public void close() {
        cleanupExecutor.shutdownNow();
        synchronized (admissionLock) {
            results.clear();
            recordsBySession.clear();
            failureRecordsBySession.clear();
            failureRecordCount = 0;
        }
        retiredResources.clear();
        retiredResponseResources.clear();
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    private static String fingerprint(Object body) {
        String value = body == null ? "" : body.toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record Result<T>(T value, boolean replayed) {
    }

    public static final class InvalidIdempotencyKeyException extends RuntimeException {
    }

    public static final class IdempotencyKeyReusedException extends RuntimeException {
    }

    private record OperationKey(String sessionScope, String endpoint, String key) {
    }

    private record Registration(OperationEntry entry, boolean owner) {
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class OperationEntry {
        private final String requestFingerprint;
        private final CompletableFuture<StoredResult> completion = new CompletableFuture<>();
        private volatile StoredResult prepared;
        private boolean retainedFailure;

    }

    private record StoredResult(Object response, RuntimeException failure) {
    }
}
