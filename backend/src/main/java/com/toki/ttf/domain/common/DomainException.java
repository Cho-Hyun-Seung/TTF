package com.toki.ttf.domain.common;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

/** Domain rule violation carrying the stable API error code. */
@Getter
@Accessors(fluent = true)
public final class DomainException extends RuntimeException {
    private final Code code;

    public DomainException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public enum Code {
        VALIDATION_ERROR,
        INVALID_STATE_TRANSITION,
        NICKNAME_TAKEN,
        ROOM_FULL,
        GAME_ALREADY_STARTED,
        NOT_ENOUGH_PARTICIPANTS,
        PARTICIPANTS_NOT_READY,
        PARTICIPANT_PERMISSION_REQUIRED,
        ROUND_NOT_FOUND,
        SPEAKER_CANNOT_VOTE,
        VOTING_NOT_OPEN
    }
}
