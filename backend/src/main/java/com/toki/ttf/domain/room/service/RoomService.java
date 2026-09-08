package com.toki.ttf.domain.room.service;

import com.toki.ttf.contract.error.ApiException;
import com.toki.ttf.contract.error.ErrorCode;
import com.toki.ttf.contract.response.GameReferenceResponse;
import com.toki.ttf.domain.common.OpaqueIdGenerator;
import com.toki.ttf.domain.room.dto.request.CreateRoomRequest;
import com.toki.ttf.domain.room.dto.request.JoinRoomRequest;
import com.toki.ttf.domain.room.dto.response.CreateRoomResponse;
import com.toki.ttf.domain.room.dto.response.JoinRoomResponse;
import com.toki.ttf.domain.room.dto.response.RoomSummaryResponse;
import com.toki.ttf.domain.room.entity.Participant;
import com.toki.ttf.domain.room.entity.Room;
import com.toki.ttf.domain.room.repository.RoomRepository;
import com.toki.ttf.domain.room.result.CreatedRoomResult;
import com.toki.ttf.domain.room.result.JoinedRoomResult;
import com.toki.ttf.domain.room.constants.RoomStatus;
import com.toki.ttf.domain.room.value.RoomSettings;
import com.toki.ttf.domain.ttf.constants.SpeakerOrder;
import com.toki.ttf.domain.ttf.constants.TtfGameStatus;
import com.toki.ttf.domain.ttf.value.TtfGameSettings;
import com.toki.ttf.infrastructure.idempotency.IdempotencyService;
import com.toki.ttf.infrastructure.ratelimit.RateLimitService;
import com.toki.ttf.infrastructure.security.SessionService;
import com.toki.ttf.infrastructure.sse.GameEventHub;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 방 생성과 조회, 참가자 관리, 방 취소를 조정하는 애플리케이션 서비스입니다.
 *
 * <p>방 단위 권한과 멱등성, 요청 빈도 제한을 검증하고 저장소 변경과 실시간 이벤트 발행을
 * 일관된 흐름으로 처리합니다. 컨트롤러에 전달할 응답 DTO도 이 계층에서 생성합니다.</p>
 */
@Service
@RequiredArgsConstructor
public class RoomService {

    private static final int CREATE_RATE_PER_MINUTE = 10;
    private static final int LOOKUP_RATE_PER_MINUTE = 60;
    private static final int JOIN_RATE_PER_MINUTE = 20;
    private static final int HOST_COMMAND_RATE_PER_SECOND = 5;

    private final RoomRepository roomRepository;
    private final SessionService sessionService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final GameEventHub eventHub;
    private final OpaqueIdGenerator ids;
    @Value("${ttf.frontend-base-url}")
    private final String frontendBaseUrl;
    @Value("${ttf.lifecycle.active-room-ttl}")
    private final Duration activeRoomTtl;
    /** 종료 상태 데이터를 정리하기 전 유지하는 유예 기간입니다. */
    @Getter
    @Accessors(fluent = true)
    @Value("${ttf.lifecycle.terminal-grace-period}")
    private final Duration terminalGracePeriod;

    /**
     * 진행자 세션을 준비하고 새 방과 활성 TTF 게임을 생성합니다.
     * 동일한 멱등성 키로 재시도하면 앞서 생성한 응답을 반환합니다.
     *
     * @return 방 생성 응답 DTO와 진행자 쿠키 설정에 사용할 원문 세션 토큰
     */
    public CreatedRoomResult createRoom(
            CreateRoomRequest request,
            String idempotencyKey,
            String currentHostToken,
            String clientAddress
    ) {
        /* 세션 생성 */
        SessionService.IssuedSession session = sessionService.prepareHostSession(currentHostToken);

        /* 멱등성 처리 */
        try {
            IdempotencyService.Result<CreateRoomResponse> result =
                    idempotencyService.executeWithoutFailureReplay(
                            session.sessionKey(),
                            "POST:/api/v1/rooms",
                            idempotencyKey,
                            request,
                            () -> {
                                rateLimitService.check(
                                        "room-create",
                                        clientAddress,
                                        CREATE_RATE_PER_MINUTE,
                                        Duration.ofMinutes(1)
                                );
                                return createRoomOnce(request, session.sessionKey());
                            }
                    );
            return new CreatedRoomResult(result.value(), session.rawToken());
        } catch (RuntimeException exception) {
            sessionService.discardHostIfEmpty(session.sessionKey());
            throw exception;
        }
    }

    /**
     * 6자리 방 코드로 참가 전 공개 가능한 방 요약을 조회합니다.
     *
     * @return 클라이언트에 전달할 방 요약 응답 DTO
     */
    public RoomSummaryResponse getRoomByCode(String code, String clientAddress) {
        rateLimitService.check("room-lookup", clientAddress, LOOKUP_RATE_PER_MINUTE, Duration.ofMinutes(1));
        if (code == null || !code.strip().matches("(?i)[A-Z0-9]{6}")) {
            throw new ApiException(ErrorCode.ROOM_NOT_FOUND);
        }
        Room room = roomRepository.findByCode(code).orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
        ensureNotExpired(room);
        synchronized (room) {
            return toSummary(room);
        }
    }

    /**
     * 참가자 세션을 준비하고 입장 가능한 방에 참가자를 등록합니다.
     * 동일한 멱등성 키로 재시도하면 중복 참가자를 만들지 않습니다.
     *
     * @return 방 참가 응답 DTO와 참가자 쿠키 설정에 사용할 원문 세션 토큰
     */
    public JoinedRoomResult joinRoom(
            String roomId,
            JoinRoomRequest request,
            String idempotencyKey,
            String currentParticipantToken,
            String clientAddress
    ) {
        Room room = requireRoom(roomId);
        ensureNotExpired(room);
        SessionService.IssuedSession session = sessionService.prepareParticipantSession(currentParticipantToken);

        try {
            IdempotencyService.Result<JoinRoomResponse> result =
                    idempotencyService.executeWithoutFailureReplay(
                            session.sessionKey(),
                            "POST:/api/v1/rooms/" + roomId + "/participants",
                            idempotencyKey,
                            request,
                            () -> {
                                rateLimitService.check(
                                        "room-join",
                                        clientAddress + ':' + roomId,
                                        JOIN_RATE_PER_MINUTE,
                                        Duration.ofMinutes(1)
                                );
                                return joinRoomOnce(room, request, session);
                            }
                    );
            return new JoinedRoomResult(result.value(), session.rawToken());
        } catch (RuntimeException exception) {
            sessionService.discardParticipantIfEmpty(session.sessionKey());
            throw exception;
        }
    }

    /**
     * 진행자 권한을 확인한 뒤 참가자를 방에서 제거하고 해당 참가자 세션을 폐기합니다.
     */
    public void removeParticipant(
            String roomId,
            String participantId,
            String idempotencyKey,
            String hostToken,
            String participantToken
    ) {
        Room room = requireRoom(roomId);
        validateOpaquePathId(participantId);
        String sessionKey = requireHost(roomId, hostToken, participantToken);
        IdempotencyService.Result<Boolean> result = idempotencyService.executeDiscardingFalseResult(
                sessionKey,
                "DELETE:/api/v1/rooms/" + roomId + "/participants/" + participantId,
                idempotencyKey,
                null,
                () -> {
                    rateLimitService.check(
                            "host-command",
                            sessionKey,
                            HOST_COMMAND_RATE_PER_SECOND,
                            Duration.ofSeconds(1)
                    );
                    long beforeVersion = room.version();
                    TtfGameStatus beforeStatus = room.activeGame().status();
                    boolean removed = roomRepository.update(roomId, current -> {
                        boolean participantRemoved = current.removeParticipant(
                                participantId, Instant.now());
                        if (participantRemoved) {
                            sessionService.revokeParticipant(roomId, participantId)
                                    .forEach(revokedSessionKey -> eventHub.removeSubject(
                                            current.activeGame().id(),
                                            GameEventHub.participantSubject(revokedSessionKey)
                                    ));
                        }
                        return participantRemoved;
                    });
                    if (removed) {
                        publish("participant.left", room);
                        publishStatusIfChanged(beforeStatus, room);
                    } else if (room.version() != beforeVersion) {
                        publish("participant.left", room);
                    }
                    return removed;
                }
        );
        if (!result.replayed() && result.value()) {
            idempotencyService.removeResponsesForResource(participantId);
        }
    }

    /**
     * 진행자 권한을 확인한 뒤 시작 전 방을 취소하고 종료 유예 기간 후 삭제를 예약합니다.
     */
    public void cancelRoom(
            String roomId,
            String idempotencyKey,
            String hostToken,
            String participantToken
    ) {
        Room room = requireRoom(roomId);
        String sessionKey = requireHost(roomId, hostToken, participantToken);
        idempotencyService.execute(
                sessionKey,
                "POST:/api/v1/rooms/" + roomId + "/commands/cancel",
                idempotencyKey,
                null,
                () -> {
                    rateLimitService.check(
                            "host-command",
                            sessionKey,
                            HOST_COMMAND_RATE_PER_SECOND,
                            Duration.ofSeconds(1)
                    );
                    roomRepository.update(roomId, current -> {
                        Instant now = Instant.now();
                        current.cancel(now);
                        current.scheduleDeletionAt(now.plus(terminalGracePeriod));
                        return null;
                    });
                    publish("game.status_changed", room);
                    return Boolean.TRUE;
                }
        );
    }

    /**
     * 게임 ID에 해당하는 만료되지 않은 TTF 방을 조회합니다.
     *
     * @return 게임을 소유한 방
     * @throws ApiException 게임이 없거나 만료되었거나 TTF 게임이 아닌 경우
     */
    public Room requireRoomByGameId(String gameId) {
        Room room = roomRepository.findByGameId(gameId)
                .orElseThrow(() -> new ApiException(ErrorCode.GAME_NOT_FOUND));
        ensureNotExpired(room);
        if (!"TTF".equals(room.activeGame().type())) {
            throw new ApiException(ErrorCode.GAME_NOT_FOUND);
        }
        return room;
    }

    /**
     * 현재 방에 유효한 진행자 자격 증명인지 확인합니다.
     *
     * @return 멱등성 및 요청 빈도 제한 식별에 사용할 진행자 세션 키
     * @throws ApiException 세션이 없거나 현재 방의 진행자가 아닌 경우
     */
    public String requireHost(String roomId, String hostToken, String participantToken) {
        if (hostToken == null || hostToken.isBlank()) {
            if (sessionService.isKnownParticipantSession(participantToken)) {
                throw new ApiException(ErrorCode.HOST_PERMISSION_REQUIRED);
            }
            throw new ApiException(ErrorCode.SESSION_REQUIRED);
        }
        return sessionService.hostSessionKey(hostToken, roomId)
                .orElseThrow(() -> sessionService.isKnownHostSession(hostToken)
                        ? new ApiException(ErrorCode.HOST_PERMISSION_REQUIRED)
                        : new ApiException(ErrorCode.SESSION_REQUIRED));
    }

    /**
     * 현재 방에 유효한 참가자 자격 증명인지 확인합니다.
     *
     * @return 참가자 ID와 내부 세션 키를 포함한 권한 정보
     * @throws ApiException 세션이 없거나 현재 방의 참가자가 아닌 경우
     */
    public SessionService.ParticipantGrant requireParticipant(
            String roomId,
            String participantToken,
            String hostToken
    ) {
        if (participantToken == null || participantToken.isBlank()) {
            if (sessionService.isKnownHostSession(hostToken)) {
                throw new ApiException(ErrorCode.PARTICIPANT_PERMISSION_REQUIRED);
            }
            throw new ApiException(ErrorCode.SESSION_REQUIRED);
        }
        return sessionService.participantGrant(participantToken, roomId)
                .orElseThrow(() -> sessionService.isKnownParticipantSession(participantToken)
                        ? new ApiException(ErrorCode.PARTICIPANT_PERMISSION_REQUIRED)
                        : new ApiException(ErrorCode.SESSION_REQUIRED));
    }

    /**
     * 방 도메인 객체를 비밀 정보가 포함되지 않은 공개 요약 응답으로 변환합니다.
     *
     * @return 클라이언트에 공개 가능한 방 요약
     */
    public static RoomSummaryResponse toSummary(Room room) {
        return new RoomSummaryResponse(
                room.id(),
                room.code(),
                room.name(),
                RoomSummaryResponse.RoomStatus.valueOf(room.status().name()),
                room.joinable(),
                room.participantCount(),
                new RoomSummaryResponse.Settings(room.settings().maxParticipants()),
                new GameReferenceResponse(room.activeGame().id(), room.activeGame().type())
        );
    }

    private CreateRoomResponse createRoomOnce(CreateRoomRequest request, String sessionKey) {
        Instant now = Instant.now();
        Room room = null;
        for (int attempt = 0; attempt < 20 && room == null; attempt++) {
            Room candidate = Room.create(
                    ids.roomId(),
                    ids.roomCode(),
                    request.name(),
                    new RoomSettings(request.settings().maxParticipants()),
                    ids.gameId(),
                    new TtfGameSettings(
                            request.game().settings().statementMaxLength(),
                            request.game().settings().votingDurationSeconds(),
                            SpeakerOrder.valueOf(request.game().settings().speakerOrder().name()),
                            request.game().settings().anonymousVoting(),
                            request.game().settings().roundCount(),
                            request.game().settings().topicIds()
                    ),
                    now,
                    now.plus(activeRoomTtl)
            );
            try {
                room = roomRepository.createRoom(candidate);
            } catch (IllegalStateException duplicate) {
                // A random identifier collision is retried without exposing identifier details.
            }
        }
        if (room == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        sessionService.grantHost(sessionKey, room.id());
        CreateRoomResponse response = new CreateRoomResponse(
                new CreateRoomResponse.Room(
                        room.id(),
                        room.code(),
                        frontendBaseUrl + "/join/" + room.code()
                ),
                new GameReferenceResponse(room.activeGame().id(), room.activeGame().type())
        );
        return response;
    }

    private JoinRoomResponse joinRoomOnce(
            Room room,
            JoinRoomRequest request,
            SessionService.IssuedSession session
    ) {
        long beforeVersion = room.version();
        TtfGameStatus beforeStatus = room.activeGame().status();
        Participant participant = roomRepository.update(room.id(), current -> {
            String participantId = sessionService.participantGrant(session.rawToken(), room.id())
                    .map(SessionService.ParticipantGrant::participantId)
                    .orElseGet(ids::participantId);
            Participant joined = current.join(participantId, request.nickname(), Instant.now());
            sessionService.grantParticipant(session.sessionKey(), room.id(), joined.id());
            return joined;
        });

        if (room.version() != beforeVersion) {
            publish("participant.joined", room);
            publishStatusIfChanged(beforeStatus, room);
        }
        JoinRoomResponse response = new JoinRoomResponse(
                room.id(),
                participant.id(),
                new GameReferenceResponse(room.activeGame().id(), room.activeGame().type())
        );
        return response;
    }

    private Room requireRoom(String roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
        ensureNotExpired(room);
        return room;
    }

    private void ensureNotExpired(Room room) {
        Instant now = Instant.now();
        boolean newlyExpired = false;
        synchronized (room) {
            if (room.status() == RoomStatus.EXPIRED) {
                throw new ApiException(ErrorCode.ROOM_EXPIRED);
            }
            if (room.status() == RoomStatus.CLOSED) {
                if (room.isDeletionDueAt(now)) {
                    throw new ApiException(ErrorCode.ROOM_EXPIRED);
                }
                return;
            }
            if (room.isActiveExpiredAt(now)) {
                room.expire(now);
                room.scheduleDeletionAt(now.plus(terminalGracePeriod));
                newlyExpired = true;
            }
        }
        if (newlyExpired) {
            publish("game.sync_required", room);
            throw new ApiException(ErrorCode.ROOM_EXPIRED);
        }
    }

    private void publishStatusIfChanged(TtfGameStatus beforeStatus, Room room) {
        if (beforeStatus != room.activeGame().status()) {
            publish("game.status_changed", room);
        }
    }

    private void publish(String event, Room room) {
        eventHub.publish(event, room.id(), room.activeGame().id(), room.version());
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:5173";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void validateOpaquePathId(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 200
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
    }

}
