package com.toki.ttf.infrastructure.scheduling;

import jakarta.annotation.PreDestroy;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class VotingScheduler {

    private final ScheduledThreadPoolExecutor executor = createExecutor();
    private final Map<String, ScheduleState> states = new ConcurrentHashMap<>();

    /**
     * The domain version is a generation token. It prevents an older round's delayed
     * post-commit scheduling from replacing a newer round or resume schedule.
     */
    public void schedule(
            String gameId,
            long generation,
            Instant deadline,
            Runnable action
    ) {
        ScheduleState state = states.computeIfAbsent(gameId, ignored -> new ScheduleState());
        synchronized (state) {
            if (state.retired || generation <= state.generation) {
                return;
            }
            cancelCurrent(state);
            state.generation = generation;
            DeadlineTask next = new DeadlineTask(generation, deadline, action);
            state.task = next;
            scheduleRun(gameId, state, next);
        }
    }

    public void cancel(String gameId, long generation) {
        ScheduleState state = states.computeIfAbsent(gameId, ignored -> new ScheduleState());
        synchronized (state) {
            if (state.retired || generation < state.generation) {
                return;
            }
            cancelCurrent(state);
            state.generation = generation;
        }
    }

    public void retire(String gameId) {
        ScheduleState state = states.computeIfAbsent(gameId, ignored -> new ScheduleState());
        synchronized (state) {
            if (state.retired) {
                return;
            }
            cancelCurrent(state);
            state.retired = true;
        }
        executor.schedule(() -> states.remove(gameId, state), 30, TimeUnit.MINUTES);
    }

    private void runIfCurrent(String gameId, ScheduleState state, DeadlineTask task) {
        synchronized (state) {
            if (state.retired || state.task != task || state.generation != task.generation) {
                return;
            }
            if (Instant.now().isBefore(task.deadline)) {
                scheduleRun(gameId, state, task);
                return;
            }
            state.task = null;
        }
        task.action.run();
    }

    private void scheduleRun(String gameId, ScheduleState state, DeadlineTask task) {
        Duration remaining = Duration.between(Instant.now(), task.deadline);
        long delay = remaining.isNegative() || remaining.isZero()
                ? 0
                : Math.max(1, remaining.toMillis());
        task.future = executor.schedule(
                () -> runIfCurrent(gameId, state, task),
                delay,
                TimeUnit.MILLISECONDS
        );
    }

    private static void cancelCurrent(ScheduleState state) {
        DeadlineTask task = state.task;
        state.task = null;
        if (task != null && task.future != null) {
            task.future.cancel(false);
        }
    }

    private static ScheduledThreadPoolExecutor createExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, task -> {
            Thread thread = new Thread(task, "ttf-voting-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    @PreDestroy
    public void close() {
        states.values().forEach(state -> {
            synchronized (state) {
                cancelCurrent(state);
                state.retired = true;
            }
        });
        states.clear();
        executor.shutdownNow();
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class DeadlineTask {
        private final long generation;
        private final Instant deadline;
        private final Runnable action;
        private volatile ScheduledFuture<?> future;

    }

    private static final class ScheduleState {
        private long generation = Long.MIN_VALUE;
        private DeadlineTask task;
        private boolean retired;
    }
}
