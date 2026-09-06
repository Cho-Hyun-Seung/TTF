package com.toki.ttf.domain.ttf.service;

import com.toki.ttf.contract.error.ApiException;
import com.toki.ttf.contract.error.ErrorCode;
import com.toki.ttf.domain.room.entity.Room;
import com.toki.ttf.domain.room.repository.RoomRepository;
import com.toki.ttf.domain.room.service.RoomService;
import com.toki.ttf.domain.room.constants.ConnectionStatus;
import com.toki.ttf.domain.ttf.dto.request.ExtendVotingRequest;
import com.toki.ttf.domain.ttf.dto.request.SaveStatementsRequest;
import com.toki.ttf.domain.ttf.dto.request.SubmitVoteRequest;
import com.toki.ttf.domain.ttf.dto.response.TtfGameSnapshotResponse;
import com.toki.ttf.domain.ttf.entity.Round;
import com.toki.ttf.domain.ttf.result.AutoCloseResult;
import com.toki.ttf.domain.ttf.result.GameMutationResult;
import com.toki.ttf.domain.ttf.result.HostCommandExecution;
import com.toki.ttf.domain.ttf.result.HostCommandResult;
import com.toki.ttf.domain.ttf.result.VoteMutationResult;
import com.toki.ttf.domain.ttf.result.VoteSubmission;
import com.toki.ttf.domain.ttf.constants.SnapshotAudience;
import com.toki.ttf.domain.ttf.constants.TtfGameStatus;
import com.toki.ttf.domain.ttf.value.StatementDraft;
import com.toki.ttf.infrastructure.idempotency.IdempotencyService;
import com.toki.ttf.infrastructure.ratelimit.RateLimitService;
import com.toki.ttf.infrastructure.scheduling.VotingScheduler;
import com.toki.ttf.infrastructure.security.SessionService;
import com.toki.ttf.infrastructure.sse.GameEventHub;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;

/**
 * TTF 게임 조회와 상태 전환을 서버 권위로 수행하는 애플리케이션 서비스입니다.
 *
 * <p>대상별 정보 공개 범위와 세션 권한을 검증하고, 원자적 상태 변경과 멱등성 처리,
 * 투표 마감 예약 및 실시간 이벤트 발행을 조정합니다.</p>
 */
@Service
@RequiredArgsConstructor
public class TtfGameService {

    private static final int STATEMENT_WRITES_PER_MINUTE = 10;
    private static final int VOTE_WRITES_PER_SECOND = 5;

    private final RoomRepository roomRepository;
    private final RoomService roomService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final VotingScheduler votingScheduler;
    private final GameEventHub eventHub;
    private final TtfGameSnapshotAssembler snapshotAssembler;
    @Value("${ttf.rate-limit.host-commands-per-second:5}")
    private final int commandRatePerSecond;

    /**
     * 지연된 투표 마감을 먼저 반영한 뒤 요청 대상에게 허용된 최신 게임 스냅샷을 생성합니다.
     * 참가자와 진행자에게는 각각의 세션 권한을 적용하고, 공개 시점 전의 정보는 포함하지 않습니다.
     *
     * @return 대상별 공개 범위가 적용된 게임 스냅샷 응답 DTO
     */
    public TtfGameSnapshotResponse snapshot(
            String gameId,
            String audienceValue,
            String hostToken,
            String participantToken
    ) {
        Room room = roomService.requireRoomByGameId(gameId);
        closeDueVotingIfNeeded(room);
        SnapshotAudience audience = SnapshotAudience.parse(audienceValue);
        synchronized (room) {
            SessionService.ParticipantGrant participantGrant = authorize(
                    room, audience, hostToken, participantToken);
            return snapshotAssembler.assemble(
                    room,
                    audience,
                    participantGrant
            );
        }
    }

    /**
     * 요청 대상의 권한을 확인하고 게임 실시간 이벤트 스트림을 구독합니다.
     * 참가자 연결은 접속과 해제 시 온라인 상태에도 반영합니다.
     *
     * @return 게임 이벤트를 전달하는 SSE emitter
     */
    public SseEmitter events(
            String gameId,
            String audienceValue,
            String hostToken,
            String participantToken,
            String lastEventId,
            String clientAddress
    ) {
        Room room = roomService.requireRoomByGameId(gameId);
        SnapshotAudience audience = SnapshotAudience.parse(audienceValue);
        synchronized (room) {
            SessionService.ParticipantGrant participantGrant = authorize(
                    room, audience, hostToken, participantToken);
            String subject = switch (audience) {
                case HOST -> "host:" + roomService.requireHost(room.id(), hostToken, participantToken);
                case PARTICIPANT -> GameEventHub.participantSubject(participantGrant.sessionKey());
                case DISPLAY -> "display:" + clientAddress;
            };
            Runnable onConnected = null;
            Runnable onDisconnected = null;
            if (audience == SnapshotAudience.PARTICIPANT) {
                String participantId = participantGrant.participantId();
                onConnected = () -> updateConnection(
                        room.id(),
                        participantId,
                        ConnectionStatus.ONLINE,
                        () -> eventHub.hasConnections(gameId, subject)
                );
                onDisconnected = () -> updateConnection(
                        room.id(),
                        participantId,
                        ConnectionStatus.OFFLINE,
                        () -> !eventHub.hasConnections(gameId, subject)
                );
            }
            try {
                return eventHub.subscribe(
                        room.id(),
                        gameId,
                        room.version(),
                        subject,
                        clientAddress,
                        lastEventId,
                        onConnected,
                        onDisconnected
                );
            } catch (GameEventHub.GameStreamClosedException exception) {
                throw new ApiException(ErrorCode.GAME_NOT_FOUND);
            }
        }
    }

    /**
     * 참가자의 문장 제출 내용을 원자적으로 저장하고 준비 상태 변경 이벤트를 발행합니다.
     */
    public void saveStatements(
            String gameId,
            SaveStatementsRequest request,
            String participantToken,
            String hostToken
    ) {
        Room room = roomService.requireRoomByGameId(gameId);
        SessionService.ParticipantGrant grant = roomService.requireParticipant(
                room.id(), participantToken, hostToken);
        rateLimitService.check(
                "statement-save",
                grant.sessionKey(),
                STATEMENT_WRITES_PER_MINUTE,
                Duration.ofMinutes(1)
        );

        GameMutationResult mutation = roomRepository.update(room.id(), current -> {
            TtfGameStatus beforeStatus = current.activeGame().status();
            long beforeVersion = current.version();
            List<StatementDraft> drafts = request.statements().stream()
                    .map(statement -> new StatementDraft(
                            statement.content(), Boolean.TRUE.equals(statement.isFake())))
                    .toList();
            current.saveStatements(grant.participantId(), drafts, Instant.now());
            return GameMutationResult.of(current, beforeVersion, beforeStatus);
        });
        if (mutation.changed()) {
            publish("participant.ready_changed", room);
            publishStatusIfChanged(mutation.beforeStatus(), room);
        }
    }

    /**
     * 참가자 투표를 원자적으로 반영합니다.
     * 마감 시각과 경합하면 서버 시각을 기준으로 먼저 투표를 닫고 요청을 거절합니다.
     */
    public void submitVote(
            String gameId,
            String roundId,
            SubmitVoteRequest request,
            String participantToken,
            String hostToken
    ) {
        validateOpaqueId(roundId);
        validateOpaqueId(request.statementId());
        Room room = roomService.requireRoomByGameId(gameId);
        SessionService.ParticipantGrant grant = roomService.requireParticipant(
                room.id(), participantToken, hostToken);
        rateLimitService.check(
                "vote",
                grant.sessionKey(),
                VOTE_WRITES_PER_SECOND,
                Duration.ofSeconds(1)
        );

        VoteMutationResult mutation = roomRepository.update(room.id(), current -> {
            Instant now = Instant.now();
            current.activeGame().requireRoundExists(roundId);
            if (current.activeGame().status() != TtfGameStatus.VOTING) {
                return new VoteMutationResult(null, false, true, current.version());
            }
            if (current.closeVotingIfDue(roundId, now)) {
                return new VoteMutationResult(null, true, false, current.version());
            }
            VoteSubmission submission = current.submitVote(
                    grant.participantId(), roundId, request.statementId(), now);
            return new VoteMutationResult(submission, false, false, current.version());
        });

        if (mutation.closedByDeadline()) {
            votingScheduler.cancel(gameId, mutation.generation());
            publish("voting.closed", room);
            publish("game.status_changed", room);
            throw new ApiException(ErrorCode.VOTING_NOT_OPEN);
        }
        if (mutation.notOpen()) {
            throw new ApiException(ErrorCode.VOTING_NOT_OPEN);
        }
        if (mutation.submission().firstVote()) {
            publish("vote.progress_changed", room);
        }
    }

    /**
     * 진행자 권한으로 게임을 시작하고 첫 라운드 시작을 알립니다.
     * 동일한 멱등성 키의 재시도는 상태와 이벤트를 중복 변경하지 않습니다.
     */
    public void start(
            String gameId,
            String key,
            String hostToken,
            String participantToken
    ) {
        HostCommandExecution execution = hostCommand(
                gameId, key, null, hostToken, participantToken, "commands/start", (room, now) -> {
            room.startGame(now);
            return HostCommandResult.atVersion(room.version());
        });
        if (!execution.replayed()) {
            publish("game.status_changed", execution.room());
            publish("round.started", execution.room());
        }
    }

    /**
     * 현재 라운드의 투표를 시작하고 서버 마감 작업을 예약합니다.
     */
    public void startVoting(
            String gameId,
            String roundId,
            String key,
            String hostToken,
            String participantToken
    ) {
        validateOpaqueId(roundId);
        HostCommandExecution execution = hostCommand(
                gameId, key, null, hostToken, participantToken,
                "rounds/" + roundId + "/commands/start-voting",
                (room, now) -> {
                    room.startVoting(roundId, now);
                    Instant deadline = room.activeGame().currentRound().orElseThrow().votingEndsAt();
                    return new HostCommandResult(
                            deadline,
                            room.activeGame().status(),
                            room.status().name(),
                            room.version()
                    );
                }
        );
        if (!execution.replayed()) {
            scheduleClose(
                    gameId,
                    roundId,
                    execution.result().deadline(),
                    execution.result().generation()
            );
            publish("voting.started", execution.room());
            publish("game.status_changed", execution.room());
        }
    }

    /**
     * 투표가 아직 열려 있으면 마감 시각을 연장하고 서버 마감 작업을 다시 예약합니다.
     */
    public void extendVoting(
            String gameId,
            String roundId,
            ExtendVotingRequest request,
            String key,
            String hostToken,
            String participantToken
    ) {
        validateOpaqueId(roundId);
        Room currentRoom = roomService.requireRoomByGameId(gameId);
        closeDueVotingIfNeeded(currentRoom);
        HostCommandExecution execution = hostCommand(
                gameId, key, request, hostToken, participantToken,
                "rounds/" + roundId + "/commands/extend-voting",
                (room, now) -> {
                    room.extendVoting(roundId, request.seconds(), now);
                    return new HostCommandResult(
                            room.activeGame().currentRound().orElseThrow().votingEndsAt(),
                            room.activeGame().status(),
                            room.status().name(),
                            room.version()
                    );
                }
        );
        if (!execution.replayed()) {
            scheduleClose(
                    gameId,
                    roundId,
                    execution.result().deadline(),
                    execution.result().generation()
            );
            publish("voting.started", execution.room());
        }
    }

    /**
     * 현재 라운드 투표를 즉시 마감하고 예약된 자동 마감 작업을 취소합니다.
     */
    public void closeVoting(
            String gameId,
            String roundId,
            String key,
            String hostToken,
            String participantToken
    ) {
        validateOpaqueId(roundId);
        HostCommandExecution execution = hostCommand(
                gameId, key, null, hostToken, participantToken,
                "rounds/" + roundId + "/commands/close-voting",
                (room, now) -> {
                    room.closeVoting(roundId, now);
                    return HostCommandResult.atVersion(room.version());
                }
        );
        if (!execution.replayed()) {
            votingScheduler.cancel(gameId, execution.result().generation());
            publish("voting.closed", execution.room());
            publish("game.status_changed", execution.room());
        }
    }

    /**
     * 마감된 라운드의 결과와 점수를 공개하고 관련 실시간 이벤트를 발행합니다.
     */
    public void revealResult(
            String gameId,
            String roundId,
            String key,
            String hostToken,
            String participantToken
    ) {
        validateOpaqueId(roundId);
        HostCommandExecution execution = hostCommand(
                gameId, key, null, hostToken, participantToken,
                "rounds/" + roundId + "/commands/reveal-result",
                (room, now) -> {
                    if (room.activeGame().status() != TtfGameStatus.VOTE_CLOSED) {
                        throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
                    }
                    long beforeVersion = room.version();
                    room.revealResult(roundId, now);
                    return new HostCommandResult(
                            null,
                            room.activeGame().status(),
                            room.version() == beforeVersion ? "unchanged" : "changed",
                            room.version()
                    );
                }
        );
        if (!execution.replayed() && "changed".equals(execution.result().marker())) {
            publish("round.result_revealed", execution.room());
            publish("score.updated", execution.room());
            publish("game.status_changed", execution.room());
        }
    }

    /**
     * 현재 라운드를 건너뛰고 다음 라운드 또는 게임 종료 상태로 전환합니다.
     */
    public void skipRound(
            String gameId,
            String roundId,
            String key,
            String hostToken,
            String participantToken
    ) {
        validateOpaqueId(roundId);
        HostCommandExecution execution = hostCommand(
                gameId, key, null, hostToken, participantToken,
                "rounds/" + roundId + "/commands/skip",
                (room, now) -> {
                    room.skipRound(roundId, now);
                    scheduleTerminalDeletionIfNeeded(room, now);
                    return new HostCommandResult(
                            null,
                            room.activeGame().status(),
                            room.status().name(),
                            room.version()
                    );
                }
        );
        if (!execution.replayed()) {
            publishAdvanceEvents(execution.room(), execution.result().generation());
        }
    }

    /**
     * 다음 라운드로 진행하며, 모든 라운드가 끝났다면 게임 종료와 데이터 정리를 예약합니다.
     */
    public void nextRound(
            String gameId,
            String key,
            String hostToken,
            String participantToken
    ) {
        HostCommandExecution execution = hostCommand(
                gameId, key, null, hostToken, participantToken,
                "commands/next-round",
                (room, now) -> {
                    room.nextRound(now);
                    scheduleTerminalDeletionIfNeeded(room, now);
                    return new HostCommandResult(
                            null,
                            room.activeGame().status(),
                            room.status().name(),
                            room.version()
                    );
                }
        );
        if (!execution.replayed()) {
            publishAdvanceEvents(execution.room(), execution.result().generation());
        }
    }

    /**
     * 게임을 일시 정지합니다. 투표 마감과 경합하면 마감을 먼저 반영하고 예약 작업을 취소합니다.
     */
    public void pause(
            String gameId,
            String key,
            String hostToken,
            String participantToken
    ) {
        HostCommandExecution execution = hostCommand(
                gameId, key, null, hostToken, participantToken, "commands/pause",
                (room, now) -> {
                    TtfGameStatus previous = room.activeGame().status();
                    boolean closedByDeadline = false;
                    if (previous == TtfGameStatus.VOTING) {
                        String currentRoundId = room.activeGame().currentRound().orElseThrow().id();
                        closedByDeadline = room.closeVotingIfDue(currentRoundId, now);
                    }
                    TtfGameStatus pausedFrom = room.activeGame().status();
                    room.pause(now);
                    return new HostCommandResult(
                            null,
                            pausedFrom,
                            closedByDeadline ? "deadline_closed" : room.status().name(),
                            room.version()
                    );
                }
        );
        if (!execution.replayed()) {
            if (execution.result().state() == TtfGameStatus.VOTING
                    || "deadline_closed".equals(execution.result().marker())) {
                votingScheduler.cancel(gameId, execution.result().generation());
            }
            if ("deadline_closed".equals(execution.result().marker())) {
                publish("voting.closed", execution.room());
            }
            publish("game.status_changed", execution.room());
        }
    }

    /**
     * 일시 정지된 게임을 재개하고, 투표 중이었다면 남은 마감 작업을 다시 예약합니다.
     */
    public void resume(
            String gameId,
            String key,
            String hostToken,
            String participantToken
    ) {
        HostCommandExecution execution = hostCommand(
                gameId, key, null, hostToken, participantToken, "commands/resume",
                (room, now) -> {
                    room.resume(now);
                    Instant deadline = room.activeGame().status() == TtfGameStatus.VOTING
                            ? room.activeGame().currentRound().orElseThrow().votingEndsAt()
                            : null;
                    return new HostCommandResult(
                            deadline,
                            room.activeGame().status(),
                            room.status().name(),
                            room.version()
                    );
                }
        );
        if (!execution.replayed()) {
            if (execution.result().state() == TtfGameStatus.VOTING) {
                String roundId = execution.room().activeGame().currentRound().orElseThrow().id();
                scheduleClose(
                        gameId,
                        roundId,
                        execution.result().deadline(),
                        execution.result().generation()
                );
                publish("voting.started", execution.room());
            }
            publish("game.status_changed", execution.room());
        }
    }

    /**
     * 진행자 명령으로 게임을 조기 종료하고 종료 데이터 정리를 예약합니다.
     */
    public void finish(
            String gameId,
            String key,
            String hostToken,
            String participantToken
    ) {
        HostCommandExecution execution = hostCommand(
                gameId, key, null, hostToken, participantToken, "commands/finish",
                (room, now) -> {
                    room.finish(now);
                    scheduleTerminalDeletionIfNeeded(room, now);
                    return HostCommandResult.atVersion(room.version());
                }
        );
        if (!execution.replayed()) {
            votingScheduler.cancel(gameId, execution.result().generation());
            publish("game.finished", execution.room());
            publish("game.status_changed", execution.room());
        }
    }

    private HostCommandExecution hostCommand(
            String gameId,
            String key,
            Object body,
            String hostToken,
            String participantToken,
            String endpoint,
            BiFunction<Room, Instant, HostCommandResult> operation
    ) {
        Room room = roomService.requireRoomByGameId(gameId);
        String sessionKey = roomService.requireHost(room.id(), hostToken, participantToken);
        IdempotencyService.Result<HostCommandResult> result = idempotencyService.execute(
                sessionKey,
                "POST:/api/v1/games/ttf/" + gameId + '/' + endpoint,
                key,
                body,
                () -> {
                    rateLimitService.check(
                            "host-command",
                            sessionKey,
                            commandRatePerSecond,
                            Duration.ofSeconds(1)
                    );
                    return roomRepository.update(
                            room.id(), current -> operation.apply(current, Instant.now()));
                }
        );
        return new HostCommandExecution(room, result.value(), result.replayed());
    }

    private SessionService.ParticipantGrant authorize(
            Room room,
            SnapshotAudience audience,
            String hostToken,
            String participantToken
    ) {
        return switch (audience) {
            case PARTICIPANT -> roomService.requireParticipant(room.id(), participantToken, hostToken);
            case HOST -> {
                roomService.requireHost(room.id(), hostToken, participantToken);
                yield null;
            }
            case DISPLAY -> null;
        };
    }

    private void closeDueVotingIfNeeded(Room room) {
        Round round;
        synchronized (room) {
            if (room.activeGame().status() != TtfGameStatus.VOTING) {
                return;
            }
            round = room.activeGame().currentRound().orElse(null);
            if (round == null || round.votingEndsAt() == null || Instant.now().isBefore(round.votingEndsAt())) {
                return;
            }
        }
        autoClose(room.activeGame().id(), round.id());
    }

    private void scheduleClose(
            String gameId,
            String roundId,
            Instant deadline,
            long generation
    ) {
        if (deadline == null) {
            return;
        }
        votingScheduler.schedule(
                gameId,
                generation,
                deadline,
                () -> autoClose(gameId, roundId)
        );
    }

    private void autoClose(String gameId, String roundId) {
        Room room;
        try {
            room = roomService.requireRoomByGameId(gameId);
        } catch (ApiException ignored) {
            return;
        }
        AutoCloseResult mutation = roomRepository.update(room.id(), current -> new AutoCloseResult(
                current.closeVotingIfDue(roundId, Instant.now()),
                current.version()
        ));
        if (mutation.closed()) {
            votingScheduler.cancel(gameId, mutation.generation());
            publish("voting.closed", room);
            publish("game.status_changed", room);
        }
    }

    private void scheduleTerminalDeletionIfNeeded(Room room, Instant now) {
        if (room.activeGame().status() == TtfGameStatus.FINISHED) {
            room.scheduleDeletionAt(now.plus(roomService.terminalGracePeriod()));
        }
    }

    private void publishAdvanceEvents(Room room, long generation) {
        publish("game.status_changed", room);
        if (room.activeGame().status() == TtfGameStatus.ROUND_INTRO) {
            publish("round.started", room);
        } else if (room.activeGame().status() == TtfGameStatus.FINISHED) {
            votingScheduler.cancel(room.activeGame().id(), generation);
            publish("game.finished", room);
        }
    }

    private void publishStatusIfChanged(TtfGameStatus before, Room room) {
        if (before != room.activeGame().status()) {
            publish("game.status_changed", room);
        }
    }

    private void publish(String event, Room room) {
        eventHub.publish(event, room.id(), room.activeGame().id(), room.version());
    }

    private void updateConnection(
            String roomId,
            String participantId,
            ConnectionStatus connectionStatus,
            BooleanSupplier stillApplicable
    ) {
        Room room;
        try {
            room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                return;
            }
            boolean changed = roomRepository.update(roomId, current -> {
                if (!stillApplicable.getAsBoolean()) {
                    return false;
                }
                return current.setParticipantConnection(
                        participantId,
                        connectionStatus,
                        Instant.now()
                );
            });
            if (changed) {
                publish("participant.left", room);
            }
        } catch (RuntimeException ignored) {
            // The participant may have been kicked or the transient room may have been deleted.
        }
    }

    private static void validateOpaqueId(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 200
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
    }

}
