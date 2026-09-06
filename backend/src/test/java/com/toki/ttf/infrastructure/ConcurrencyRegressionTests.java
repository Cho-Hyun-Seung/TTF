package com.toki.ttf.infrastructure;

import com.toki.ttf.domain.common.OpaqueIdGenerator;
import com.toki.ttf.domain.room.dto.request.CreateRoomRequest;
import com.toki.ttf.domain.room.dto.request.JoinRoomRequest;
import com.toki.ttf.domain.room.entity.Room;
import com.toki.ttf.domain.room.repository.RoomRepository;
import com.toki.ttf.domain.room.result.CreatedRoomResult;
import com.toki.ttf.domain.room.result.JoinedRoomResult;
import com.toki.ttf.domain.room.service.RoomService;
import com.toki.ttf.infrastructure.idempotency.IdempotencyService;
import com.toki.ttf.infrastructure.persistence.InMemoryRoomRepository;
import com.toki.ttf.infrastructure.ratelimit.RateLimitService;
import com.toki.ttf.infrastructure.scheduling.VotingScheduler;
import com.toki.ttf.infrastructure.security.SessionService;
import com.toki.ttf.infrastructure.sse.GameEventHub;
import com.toki.ttf.domain.ttf.service.TtfGameService;
import com.toki.ttf.domain.ttf.service.TtfGameSnapshotAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyRegressionTests {

    @Test
    void votingSchedulerIgnoresLateOlderScheduleAndCancel() throws Exception {
        VotingScheduler scheduler = new VotingScheduler();
        try {
            CountDownLatch latestRan = new CountDownLatch(1);
            AtomicInteger latestRuns = new AtomicInteger();
            AtomicInteger staleRuns = new AtomicInteger();

            scheduler.schedule("game_generation", 20, Instant.now().plusMillis(150), () -> {
                latestRuns.incrementAndGet();
                latestRan.countDown();
            });

            // These calls model delayed post-commit work from an older request.
            scheduler.schedule("game_generation", 19, Instant.now().plusMillis(10), staleRuns::incrementAndGet);
            scheduler.cancel("game_generation", 19);

            assertThat(latestRan.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(latestRuns).hasValue(1);
            assertThat(staleRuns).hasValue(0);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void votingSchedulerReschedulesAnEarlyWakeupInsteadOfRunningTheAction() throws Exception {
        VotingScheduler scheduler = new VotingScheduler();
        try {
            CountDownLatch ran = new CountDownLatch(1);
            AtomicInteger runs = new AtomicInteger();
            AtomicReference<Instant> ranAt = new AtomicReference<>();
            Instant deadline = Instant.now().plusMillis(300);

            scheduler.schedule("game_early", 1, deadline, () -> {
                ranAt.set(Instant.now());
                runs.incrementAndGet();
                ran.countDown();
            });

            invokeCurrentTaskEarly(scheduler, "game_early");

            assertThat(ran.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(ranAt.get()).isAfterOrEqualTo(deadline);
            assertThat(runs).hasValue(1);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void votingSchedulerImmediatelyRemovesSupersededTasksFromItsQueue() throws Exception {
        VotingScheduler scheduler = new VotingScheduler();
        try {
            Instant farFuture = Instant.now().plus(Duration.ofHours(12));
            for (long generation = 1; generation <= 1_000; generation++) {
                scheduler.schedule("game_queue", generation, farFuture, () -> {
                });
            }

            ScheduledThreadPoolExecutor executor = schedulerExecutor(scheduler);
            assertThat(executor.getQueue()).hasSize(1);

            scheduler.cancel("game_queue", 1_001);
            assertThat(executor.getQueue()).isEmpty();
        } finally {
            scheduler.close();
        }
    }

    @Test
    void kickAndSameBrowserRejoinLeaveTheReplacementParticipantAuthorized() throws Exception {
        BlockingRoomRepository repository = new BlockingRoomRepository();
        SessionService sessions = new SessionService(false);
        GameEventHub eventHub = new GameEventHub();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kick-request");
            thread.setDaemon(true);
            return thread;
        });
        try {
            RoomService service = new RoomService(
                    repository,
                    sessions,
                    new IdempotencyService(),
                    new RateLimitService(),
                    eventHub,
                    new OpaqueIdGenerator(),
                    "http://localhost:5173",
                    Duration.ofHours(6),
                    Duration.ofMinutes(5)
            );

            CreatedRoomResult created = service.createRoom(
                    createRequest(), key(), null, "127.0.0.1");
            String roomId = created.response().room().id();
            JoinedRoomResult original = service.joinRoom(
                    roomId, new JoinRoomRequest("player"), key(), null, "127.0.0.1");

            Future<?> kick = executor.submit(() -> {
                repository.blockThisThreadAfterNextUpdate();
                service.removeParticipant(
                        roomId,
                        original.response().participantId(),
                        key(),
                        created.sessionToken(),
                        null
                );
            });

            assertThat(repository.awaitBlockedUpdate()).isTrue();
            JoinedRoomResult replacement = service.joinRoom(
                    roomId,
                    new JoinRoomRequest("player"),
                    key(),
                    original.sessionToken(),
                    "127.0.0.1"
            );
            repository.releaseBlockedUpdate();
            kick.get(2, TimeUnit.SECONDS);

            SessionService.ParticipantGrant grant = sessions.participantGrant(
                    replacement.sessionToken(), roomId).orElseThrow();
            assertThat(grant.participantId()).isEqualTo(replacement.response().participantId());
            assertThat(repository.findById(roomId).orElseThrow()
                    .participant(grant.participantId())).isPresent();
        } finally {
            repository.releaseBlockedUpdate();
            executor.shutdownNow();
            eventHub.close();
        }
    }

    @Test
    void detachedSubscriberDisconnectReleasesItsConnectionReservation() throws Exception {
        GameEventHub eventHub = new GameEventHub();
        String gameId = "game_detached";
        String subject = "participant:detached-session";
        String clientAddress = "192.0.2.10";
        try {
            eventHub.subscribe(
                    "room_detached",
                    gameId,
                    1,
                    subject,
                    clientAddress,
                    null,
                    null,
                    null
            );

            Object stream = fieldMap(eventHub, "streams").get(gameId);
            Object subscriber;
            synchronized (stream) {
                List<?> subscribers = fieldList(stream, "subscribers");
                subscriber = subscribers.get(0);
                subscribers.clear();
            }

            invokeSubscriberMethod(eventHub, "disconnect", stream, subscriber, false);
            invokeSubscriberMethod(eventHub, "closeDetachedSubscriber", null, subscriber, true);

            assertThat(fieldMap(eventHub, "connectionsBySubject")).doesNotContainKey(subject);
            assertThat(fieldMap(eventHub, "connectionsByClientAddress")).doesNotContainKey(clientAddress);
        } finally {
            eventHub.close();
        }
    }

    @Test
    void kickCannotLeaveASubscriptionAuthorizedFromBeforeTheKick() throws Exception {
        SignalingRoomRepository repository = new SignalingRoomRepository();
        SessionService sessions = new SessionService(false);
        IdempotencyService idempotency = new IdempotencyService();
        RateLimitService rateLimits = new RateLimitService();
        BlockingSubscribeEventHub eventHub = new BlockingSubscribeEventHub();
        VotingScheduler scheduler = new VotingScheduler();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            RoomService roomService = new RoomService(
                    repository,
                    sessions,
                    idempotency,
                    rateLimits,
                    eventHub,
                    new OpaqueIdGenerator(),
                    "http://localhost:5173",
                    Duration.ofHours(6),
                    Duration.ofMinutes(5)
            );
            TtfGameService gameService = new TtfGameService(
                    repository,
                    roomService,
                    idempotency,
                    rateLimits,
                    scheduler,
                    eventHub,
                    new TtfGameSnapshotAssembler(),
                    1_000
            );

            CreatedRoomResult created = roomService.createRoom(
                    createRequest(), key(), null, "198.51.100.10");
            String roomId = created.response().room().id();
            String gameId = created.response().game().id();
            JoinedRoomResult joined = roomService.joinRoom(
                    roomId,
                    new JoinRoomRequest("player"),
                    key(),
                    null,
                    "198.51.100.11"
            );
            String sessionKey = sessions.participantGrant(joined.sessionToken(), roomId)
                    .orElseThrow()
                    .sessionKey();

            Future<SseEmitter> subscription = executor.submit(() -> gameService.events(
                    gameId,
                    "participant",
                    null,
                    joined.sessionToken(),
                    null,
                    "198.51.100.11"
            ));
            assertThat(eventHub.awaitSubscribeEntered()).isTrue();

            repository.signalNextFind();
            Future<?> kick = executor.submit(() -> roomService.removeParticipant(
                    roomId,
                    joined.response().participantId(),
                    key(),
                    created.sessionToken(),
                    null
            ));
            assertThat(repository.awaitSignaledFind()).isTrue();

            try {
                kick.get(200, TimeUnit.MILLISECONDS);
                throw new AssertionError("kick passed the in-flight authorize/subscribe boundary");
            } catch (TimeoutException expected) {
                // The event subscription owns the room lock until it is registered.
            }

            eventHub.releaseSubscribe();
            subscription.get(2, TimeUnit.SECONDS);
            kick.get(2, TimeUnit.SECONDS);

            assertThat(eventHub.hasConnections(
                    gameId,
                    GameEventHub.participantSubject(sessionKey)
            )).isFalse();
        } finally {
            eventHub.releaseSubscribe();
            executor.shutdownNow();
            scheduler.close();
            eventHub.close();
            rateLimits.close();
        }
    }

    private static void invokeCurrentTaskEarly(VotingScheduler scheduler, String gameId) throws Exception {
        Field statesField = VotingScheduler.class.getDeclaredField("states");
        statesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> states = (Map<String, Object>) statesField.get(scheduler);
        Object state = states.get(gameId);

        Field taskField = state.getClass().getDeclaredField("task");
        taskField.setAccessible(true);
        Object task = taskField.get(state);

        Method runIfCurrent = VotingScheduler.class.getDeclaredMethod(
                "runIfCurrent", String.class, state.getClass(), task.getClass());
        runIfCurrent.setAccessible(true);
        runIfCurrent.invoke(scheduler, gameId, state, task);
    }

    private static ScheduledThreadPoolExecutor schedulerExecutor(VotingScheduler scheduler) throws Exception {
        Field executorField = VotingScheduler.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        return (ScheduledThreadPoolExecutor) executorField.get(scheduler);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldMap(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getSuperclass() == Object.class
                ? target.getClass().getDeclaredField(fieldName)
                : GameEventHub.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, Object>) field.get(target);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> fieldList(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (List<Object>) field.get(target);
    }

    private static void invokeSubscriberMethod(
            GameEventHub eventHub,
            String methodName,
            Object stream,
            Object subscriber,
            boolean complete
    ) throws Exception {
        Method method = stream == null
                ? GameEventHub.class.getDeclaredMethod(methodName, subscriber.getClass(), boolean.class)
                : GameEventHub.class.getDeclaredMethod(
                methodName, stream.getClass(), subscriber.getClass(), boolean.class);
        method.setAccessible(true);
        if (stream == null) {
            method.invoke(eventHub, subscriber, complete);
        } else {
            method.invoke(eventHub, stream, subscriber, complete);
        }
    }

    private static CreateRoomRequest createRequest() {
        return new CreateRoomRequest(
                "concurrency room",
                new CreateRoomRequest.RoomSettings(10),
                new CreateRoomRequest.Game(
                        CreateRoomRequest.GameType.TTF,
                        new CreateRoomRequest.TtfSettings(
                                100,
                                60,
                                CreateRoomRequest.SpeakerOrder.JOIN_ORDER,
                                false
                        )
                )
        );
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static final class BlockingRoomRepository implements RoomRepository {
        private final InMemoryRoomRepository delegate = new InMemoryRoomRepository();
        private final CountDownLatch updateReturnedFromDelegate = new CountDownLatch(1);
        private final CountDownLatch allowUpdateToReturn = new CountDownLatch(1);
        private final ThreadLocal<Boolean> blockAfterUpdate = ThreadLocal.withInitial(() -> false);

        void blockThisThreadAfterNextUpdate() {
            blockAfterUpdate.set(true);
        }

        boolean awaitBlockedUpdate() throws InterruptedException {
            return updateReturnedFromDelegate.await(2, TimeUnit.SECONDS);
        }

        void releaseBlockedUpdate() {
            allowUpdateToReturn.countDown();
        }

        @Override
        public Room createRoom(Room room) {
            return delegate.createRoom(room);
        }

        @Override
        public Optional<Room> findById(String roomId) {
            return delegate.findById(roomId);
        }

        @Override
        public Optional<Room> findByCode(String code) {
            return delegate.findByCode(code);
        }

        @Override
        public Optional<Room> findByGameId(String gameId) {
            return delegate.findByGameId(gameId);
        }

        @Override
        public List<Room> findAll() {
            return delegate.findAll();
        }

        @Override
        public <T> T update(String roomId, RoomMutation<T> mutation) {
            T result = delegate.update(roomId, mutation);
            if (blockAfterUpdate.get()) {
                blockAfterUpdate.remove();
                updateReturnedFromDelegate.countDown();
                try {
                    if (!allowUpdateToReturn.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release the blocked update");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("blocked update was interrupted", exception);
                }
            }
            return result;
        }

        @Override
        public void deleteById(String roomId) {
            delegate.deleteById(roomId);
        }

        @Override
        public int deleteExpired(Instant now) {
            return delegate.deleteExpired(now);
        }
    }

    private static final class SignalingRoomRepository implements RoomRepository {
        private final InMemoryRoomRepository delegate = new InMemoryRoomRepository();
        private final AtomicBoolean signalNextFind = new AtomicBoolean();
        private final AtomicReference<CountDownLatch> findCalled =
                new AtomicReference<>(new CountDownLatch(1));

        void signalNextFind() {
            findCalled.set(new CountDownLatch(1));
            signalNextFind.set(true);
        }

        boolean awaitSignaledFind() throws InterruptedException {
            return findCalled.get().await(2, TimeUnit.SECONDS);
        }

        @Override
        public Room createRoom(Room room) {
            return delegate.createRoom(room);
        }

        @Override
        public Optional<Room> findById(String roomId) {
            if (signalNextFind.compareAndSet(true, false)) {
                findCalled.get().countDown();
            }
            return delegate.findById(roomId);
        }

        @Override
        public Optional<Room> findByCode(String code) {
            return delegate.findByCode(code);
        }

        @Override
        public Optional<Room> findByGameId(String gameId) {
            return delegate.findByGameId(gameId);
        }

        @Override
        public List<Room> findAll() {
            return delegate.findAll();
        }

        @Override
        public <T> T update(String roomId, RoomMutation<T> mutation) {
            return delegate.update(roomId, mutation);
        }

        @Override
        public void deleteById(String roomId) {
            delegate.deleteById(roomId);
        }

        @Override
        public int deleteExpired(Instant now) {
            return delegate.deleteExpired(now);
        }
    }

    private static final class BlockingSubscribeEventHub extends GameEventHub {
        private final CountDownLatch subscribeEntered = new CountDownLatch(1);
        private final CountDownLatch allowSubscribe = new CountDownLatch(1);

        @Override
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
            subscribeEntered.countDown();
            try {
                if (!allowSubscribe.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release SSE subscribe");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("SSE subscribe was interrupted", exception);
            }
            return super.subscribe(
                    roomId,
                    gameId,
                    currentVersion,
                    subject,
                    clientAddress,
                    lastEventId,
                    onConnected,
                    onDisconnected
            );
        }

        boolean awaitSubscribeEntered() throws InterruptedException {
            return subscribeEntered.await(2, TimeUnit.SECONDS);
        }

        void releaseSubscribe() {
            allowSubscribe.countDown();
        }
    }
}
