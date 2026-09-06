package com.toki.ttf.infrastructure.scheduling;

import com.toki.ttf.domain.room.entity.Room;
import com.toki.ttf.domain.room.constants.RoomStatus;
import com.toki.ttf.domain.room.value.RoomSettings;
import com.toki.ttf.domain.room.repository.RoomUnavailableException;
import com.toki.ttf.domain.ttf.constants.SpeakerOrder;
import com.toki.ttf.domain.ttf.value.TtfGameSettings;
import com.toki.ttf.infrastructure.idempotency.IdempotencyService;
import com.toki.ttf.infrastructure.persistence.InMemoryRoomRepository;
import com.toki.ttf.infrastructure.security.SessionService;
import com.toki.ttf.infrastructure.sse.GameEventHub;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomLifecycleManagerTests {

    @Test
    void activeExpiryKeepsResourcesThroughGraceThenCleansEverything() throws Exception {
        InMemoryRoomRepository rooms = new InMemoryRoomRepository();
        SessionService sessions = new SessionService(false);
        IdempotencyService idempotency = new IdempotencyService();
        GameEventHub events = new GameEventHub();
        VotingScheduler votingScheduler = new VotingScheduler();
        Duration grace = Duration.ofMinutes(5);
        RoomLifecycleManager lifecycle = new RoomLifecycleManager(
                rooms,
                sessions,
                idempotency,
                events,
                votingScheduler,
                grace
        );

        Instant createdAt = Instant.now();
        Instant activeExpiry = createdAt.plus(Duration.ofHours(1));
        String roomId = "room_lifecycle";
        String gameId = "game_lifecycle";
        String participantId = "participant_lifecycle";
        Room room = Room.create(
                roomId,
                "LIFE01",
                "Lifecycle room",
                new RoomSettings(10),
                gameId,
                new TtfGameSettings(100, 60, SpeakerOrder.JOIN_ORDER, false),
                createdAt,
                activeExpiry
        );

        try {
            rooms.createRoom(room);
            rooms.update(roomId, current -> {
                current.join(participantId, "player", createdAt);
                return null;
            });

            SessionService.IssuedSession host = sessions.prepareHostSession(null);
            sessions.grantHost(host.sessionKey(), roomId);
            SessionService.IssuedSession participant = sessions.prepareParticipantSession(null);
            sessions.grantParticipant(participant.sessionKey(), roomId, participantId);

            String participantSubject = GameEventHub.participantSubject(participant.sessionKey());
            events.subscribe(
                    roomId,
                    gameId,
                    room.version(),
                    participantSubject,
                    "192.0.2.20",
                    null,
                    null,
                    null
            );

            idempotency.execute(
                    host.sessionKey(),
                    "POST:/api/v1/rooms/" + roomId + "/commands/test",
                    "lifecycle-room-key",
                    null,
                    () -> Boolean.TRUE
            );
            idempotency.execute(
                    participant.sessionKey(),
                    "POST:/api/v1/games/ttf/" + gameId + "/commands/test",
                    "lifecycle-game-key",
                    null,
                    () -> Boolean.TRUE
            );
            CountDownLatch obsoleteVotingTask = new CountDownLatch(1);
            votingScheduler.schedule(
                    gameId,
                    room.version(),
                    Instant.now().plus(Duration.ofHours(12)),
                    obsoleteVotingTask::countDown
            );

            assertThat(storedResultCount(idempotency)).isEqualTo(2);
            assertThat(events.hasConnections(gameId, participantSubject)).isTrue();

            lifecycle.sweep(activeExpiry);

            Instant deletionAt = activeExpiry.plus(grace);
            assertThat(room.status()).isEqualTo(RoomStatus.EXPIRED);
            assertThat(room.deletionAt()).isEqualTo(deletionAt);
            assertThat(rooms.findById(roomId)).contains(room);
            assertThat(sessions.hostSessionKey(host.rawToken(), roomId)).contains(host.sessionKey());
            assertThat(sessions.participantGrant(participant.rawToken(), roomId))
                    .contains(new SessionService.ParticipantGrant(participant.sessionKey(), participantId));
            assertThat(events.hasConnections(gameId, participantSubject)).isTrue();
            assertThat(storedResultCount(idempotency)).isEqualTo(2);

            lifecycle.sweep(deletionAt.minusNanos(1));

            assertThat(rooms.findById(roomId)).contains(room);
            assertThat(events.hasConnections(gameId, participantSubject)).isTrue();
            assertThat(storedResultCount(idempotency)).isEqualTo(2);

            lifecycle.sweep(deletionAt);

            assertThat(rooms.findById(roomId)).isEmpty();
            assertThat(rooms.findByGameId(gameId)).isEmpty();
            assertThat(rooms.findByCode("LIFE01")).isEmpty();
            assertThat(sessions.isKnownHostSession(host.rawToken())).isFalse();
            assertThat(sessions.isKnownParticipantSession(participant.rawToken())).isFalse();
            assertThat(events.hasConnections(gameId, participantSubject)).isFalse();
            assertThat(storedResultCount(idempotency)).isZero();
            assertThatThrownBy(() -> events.subscribe(
                    roomId,
                    gameId,
                    room.version(),
                    participantSubject,
                    "192.0.2.20",
                    null,
                    null,
                    null
            )).isInstanceOf(GameEventHub.GameStreamClosedException.class);
            assertThatThrownBy(() -> idempotency.execute(
                    host.sessionKey(),
                    "POST:/api/v1/rooms/" + roomId + "/commands/test",
                    "new-key-after-retirement",
                    null,
                    () -> Boolean.TRUE
            )).isInstanceOf(RoomUnavailableException.class);

            votingScheduler.schedule(
                    gameId,
                    Long.MAX_VALUE,
                    Instant.now(),
                    obsoleteVotingTask::countDown
            );
            assertThat(obsoleteVotingTask.await(100, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            lifecycle.close();
            votingScheduler.close();
            events.close();
            idempotency.close();
        }
    }

    private static int storedResultCount(IdempotencyService service) throws Exception {
        Field results = IdempotencyService.class.getDeclaredField("results");
        results.setAccessible(true);
        return ((Map<?, ?>) results.get(service)).size();
    }
}
