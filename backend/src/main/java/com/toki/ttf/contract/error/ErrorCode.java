package com.toki.ttf.contract.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요."),
    SESSION_REQUIRED(HttpStatus.UNAUTHORIZED, "세션이 필요합니다. 다시 접속해 주세요."),
    HOST_PERMISSION_REQUIRED(HttpStatus.FORBIDDEN, "진행자 권한이 필요합니다."),
    PARTICIPANT_PERMISSION_REQUIRED(HttpStatus.FORBIDDEN, "해당 방의 참가자 권한이 필요합니다."),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "방을 찾을 수 없습니다."),
    GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "게임을 찾을 수 없습니다."),
    ROUND_NOT_FOUND(HttpStatus.NOT_FOUND, "라운드를 찾을 수 없습니다."),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서는 실행할 수 없습니다."),
    NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임이에요. 다른 이름을 입력해 주세요."),
    ROOM_FULL(HttpStatus.CONFLICT, "방 정원이 가득 찼습니다."),
    GAME_ALREADY_STARTED(HttpStatus.CONFLICT, "이미 시작한 게임에는 참가할 수 없습니다."),
    NOT_ENOUGH_PARTICIPANTS(HttpStatus.CONFLICT, "게임을 시작하려면 참가자가 2명 이상 필요합니다."),
    PARTICIPANTS_NOT_READY(HttpStatus.CONFLICT, "아직 준비되지 않은 참가자가 있습니다."),
    SPEAKER_CANNOT_VOTE(HttpStatus.CONFLICT, "발표자는 자신의 라운드에 투표할 수 없습니다."),
    VOTING_NOT_OPEN(HttpStatus.CONFLICT, "현재 투표할 수 없습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 요청 키를 다른 요청에 사용할 수 없습니다."),
    ROOM_EXPIRED(HttpStatus.GONE, "만료된 방입니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String message;

}
