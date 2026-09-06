package com.toki.ttf.infrastructure.idempotency;

import com.toki.ttf.domain.common.DomainException;
import com.toki.ttf.infrastructure.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceSecurityTests {

    @Test
    void concurrentRequestsWithSameKeyExecuteOperationOnlyOnce() throws Exception {
        IdempotencyService service = new IdempotencyService();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch secondRequestStarted = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        try {
            Future<IdempotencyService.Result<String>> first = executor.submit(() -> service.execute(
                    "session",
                    "/api/v1/rooms",
                    "same-key",
                    "same-body",
                    () -> {
                        executions.incrementAndGet();
                        operationEntered.countDown();
                        await(releaseOperation);
                        return "created";
                    }
            ));
            assertThat(operationEntered.await(2, TimeUnit.SECONDS)).isTrue();

            Future<IdempotencyService.Result<String>> second = executor.submit(() -> {
                secondRequestStarted.countDown();
                return service.execute(
                        "session",
                        "/api/v1/rooms",
                        "same-key",
                        "same-body",
                        () -> {
                            executions.incrementAndGet();
                            return "duplicate";
                        }
                );
            });
            assertThat(secondRequestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            releaseOperation.countDown();

            List<IdempotencyService.Result<String>> results = List.of(
                    first.get(2, TimeUnit.SECONDS),
                    second.get(2, TimeUnit.SECONDS)
            );
            assertThat(executions).hasValue(1);
            assertThat(results).extracting(IdempotencyService.Result::value)
                    .containsExactly("created", "created");
            assertThat(results).extracting(IdempotencyService.Result::replayed)
                    .containsExactlyInAnyOrder(false, true);
        } finally {
            releaseOperation.countDown();
            executor.shutdownNow();
            service.close();
        }
    }

    @Test
    void rateLimitedFailureIsNotSavedForReplay() {
        IdempotencyService service = new IdempotencyService();
        AtomicInteger successfulExecutions = new AtomicInteger();
        try {
            assertThatThrownBy(() -> service.execute(
                    "session",
                    "/api/v1/rooms",
                    "retryable-key",
                    "same-body",
                    () -> {
                        throw new RateLimitService.RateLimitExceededException(3);
                    }
            )).isInstanceOf(RateLimitService.RateLimitExceededException.class);

            IdempotencyService.Result<String> retry = service.execute(
                    "session",
                    "/api/v1/rooms",
                    "retryable-key",
                    "same-body",
                    () -> {
                        successfulExecutions.incrementAndGet();
                        return "created-after-retry";
                    }
            );

            assertThat(successfulExecutions).hasValue(1);
            assertThat(retry.value()).isEqualTo("created-after-retry");
            assertThat(retry.replayed()).isFalse();
        } finally {
            service.close();
        }
    }

    @Test
    void replayableFailuresAreCompactAndBoundedWithoutBlockingSuccessfulRecords() throws Exception {
        IdempotencyService service = new IdempotencyService();
        try {
            DomainException first = failingDomainCall(service, "failure-0");
            assertThat(first.getStackTrace()).isEmpty();
            for (int index = 1; index < 128; index++) {
                failingDomainCall(service, "failure-" + index);
            }
            assertThat(storedResults(service)).hasSize(128);

            AtomicInteger overflowExecutions = new AtomicInteger();
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> service.execute(
                        "bounded-session",
                        "/api/v1/games/ttf/game/commands/start",
                        "overflow-failure",
                        null,
                        () -> {
                            overflowExecutions.incrementAndGet();
                            throw new DomainException(
                                    DomainException.Code.INVALID_STATE_TRANSITION,
                                    "invalid state"
                            );
                        }
                )).isInstanceOf(DomainException.class);
            }
            assertThat(overflowExecutions).hasValue(2);
            assertThat(storedResults(service)).hasSize(128);

            IdempotencyService.Result<String> success = service.execute(
                    "bounded-session",
                    "/api/v1/games/ttf/game/commands/start",
                    "successful-key",
                    null,
                    () -> "ok"
            );
            assertThat(success.value()).isEqualTo("ok");
            assertThat(storedResults(service)).hasSize(129);
        } finally {
            service.close();
        }
    }

    @Test
    void falseNoOpResultsAreNotRetained() throws Exception {
        IdempotencyService service = new IdempotencyService();
        AtomicInteger executions = new AtomicInteger();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                IdempotencyService.Result<Boolean> result = service.executeDiscardingFalseResult(
                        "session",
                        "/api/v1/rooms/room/participants/missing",
                        "same-key",
                        null,
                        () -> {
                            executions.incrementAndGet();
                            return Boolean.FALSE;
                        }
                );
                assertThat(result.value()).isFalse();
                assertThat(result.replayed()).isFalse();
            }
            assertThat(executions).hasValue(2);
            assertThat(storedResults(service)).isEmpty();
        } finally {
            service.close();
        }
    }

    private static DomainException failingDomainCall(IdempotencyService service, String key) {
        try {
            service.execute(
                    "bounded-session",
                    "/api/v1/games/ttf/game/commands/start",
                    key,
                    null,
                    () -> {
                        throw new DomainException(
                                DomainException.Code.INVALID_STATE_TRANSITION,
                                "invalid state"
                        );
                    }
            );
            throw new AssertionError("a domain failure was expected");
        } catch (DomainException exception) {
            return exception;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> storedResults(IdempotencyService service) throws Exception {
        Field field = IdempotencyService.class.getDeclaredField("results");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(service);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent request");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent request was interrupted", exception);
        }
    }
}
