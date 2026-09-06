package com.toki.ttf.infrastructure.scheduling;

import com.toki.ttf.domain.room.entity.Room;
import com.toki.ttf.domain.room.constants.RoomStatus;
import com.toki.ttf.domain.room.repository.RoomRepository;
import com.toki.ttf.infrastructure.idempotency.IdempotencyService;
import com.toki.ttf.infrastructure.security.SessionService;
import com.toki.ttf.infrastructure.sse.GameEventHub;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RoomLifecycleManager {

    private final RoomRepository roomRepository;
    private final SessionService sessionService;
    private final IdempotencyService idempotencyService;
    private final GameEventHub eventHub;
    private final VotingScheduler votingScheduler;
    @Value("${ttf.lifecycle.terminal-grace-period:PT5M}")
    private final Duration terminalGracePeriod;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ttf-room-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void startCleanupSchedule() {
        executor.scheduleWithFixedDelay(this::safeSweep, 30, 30, TimeUnit.SECONDS);
    }

    public void sweep(Instant now) {
        List<Room> remove = new ArrayList<>();
        List<Room> expired = new ArrayList<>();
        for (Room room : roomRepository.findAll()) {
            synchronized (room) {
                if (!room.isExpiredAt(now)) {
                    continue;
                }
                if (room.status() == RoomStatus.OPEN || room.status() == RoomStatus.IN_GAME) {
                    room.expire(now);
                    room.scheduleDeletionAt(now.plus(terminalGracePeriod));
                    expired.add(room);
                } else {
                    remove.add(room);
                }
            }
        }
        expired.forEach(room -> eventHub.publish(
                "game.sync_required",
                room.id(),
                room.activeGame().id(),
                room.version()
        ));
        remove.forEach(this::remove);
    }

    private void remove(Room room) {
        votingScheduler.retire(room.activeGame().id());
        sessionService.revokeRoom(room.id());
        idempotencyService.removeForResource(room.id());
        idempotencyService.removeForResource(room.activeGame().id());
        eventHub.removeGame(room.activeGame().id());
        roomRepository.deleteById(room.id());
    }

    private void safeSweep() {
        try {
            sweep(Instant.now());
        } catch (RuntimeException ignored) {
            // The next sweep retries cleanup. No room contents or credentials are logged.
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
