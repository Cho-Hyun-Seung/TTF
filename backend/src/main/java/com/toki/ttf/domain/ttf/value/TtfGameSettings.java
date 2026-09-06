package com.toki.ttf.domain.ttf.value;

import com.toki.ttf.domain.common.DomainException;
import com.toki.ttf.domain.ttf.constants.SpeakerOrder;

import java.util.Objects;

public record TtfGameSettings(
        int statementMinLength,
        int statementMaxLength,
        int votingDurationSeconds,
        SpeakerOrder speakerOrder,
        boolean anonymousVoting
) {
    public static final int MVP_STATEMENT_MIN_LENGTH = 5;

    public TtfGameSettings {
        if (statementMinLength != MVP_STATEMENT_MIN_LENGTH) {
            throw validation("MVP 문장 최소 길이는 5자로 고정됩니다.");
        }
        if (statementMaxLength < 20 || statementMaxLength > 200) {
            throw validation("문장 최대 길이는 20자 이상 200자 이하여야 합니다.");
        }
        if (statementMaxLength < statementMinLength) {
            throw validation("문장 최대 길이는 최소 길이보다 작을 수 없습니다.");
        }
        if (votingDurationSeconds < 15 || votingDurationSeconds > 180) {
            throw validation("투표 제한 시간은 15초 이상 180초 이하여야 합니다.");
        }
        Objects.requireNonNull(speakerOrder, "speakerOrder");
    }

    public TtfGameSettings(
            int statementMaxLength,
            int votingDurationSeconds,
            SpeakerOrder speakerOrder,
            boolean anonymousVoting
    ) {
        this(MVP_STATEMENT_MIN_LENGTH, statementMaxLength, votingDurationSeconds, speakerOrder, anonymousVoting);
    }

    private static DomainException validation(String message) {
        return new DomainException(DomainException.Code.VALIDATION_ERROR, message);
    }
}
