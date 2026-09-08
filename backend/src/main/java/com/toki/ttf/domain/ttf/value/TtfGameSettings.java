package com.toki.ttf.domain.ttf.value;

import com.toki.ttf.domain.common.DomainException;
import com.toki.ttf.domain.ttf.constants.SpeakerOrder;
import com.toki.ttf.domain.ttf.constants.TtfTopic;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record TtfGameSettings(
        int statementMinLength,
        int statementMaxLength,
        int votingDurationSeconds,
        SpeakerOrder speakerOrder,
        boolean anonymousVoting,
        int roundCount,
        List<TtfTopic> topics
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
        topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
        if (roundCount < 1 || roundCount > TtfTopic.catalog().size()) {
            throw validation("라운드 수는 1개 이상 " + TtfTopic.catalog().size() + "개 이하여야 합니다.");
        }
        if (topics.size() != roundCount) {
            throw validation("선택한 주제 수는 라운드 수와 같아야 합니다.");
        }
        if (topics.stream().anyMatch(Objects::isNull)
                || new HashSet<>(topics).size() != topics.size()) {
            throw validation("라운드 주제는 중복 없이 선택해야 합니다.");
        }
    }

    public TtfGameSettings(
            int statementMaxLength,
            int votingDurationSeconds,
            SpeakerOrder speakerOrder,
            boolean anonymousVoting
    ) {
        this(
                MVP_STATEMENT_MIN_LENGTH,
                statementMaxLength,
                votingDurationSeconds,
                speakerOrder,
                anonymousVoting,
                1,
                List.of(TtfTopic.TRAVEL)
        );
    }

    public TtfGameSettings(
            int statementMaxLength,
            int votingDurationSeconds,
            SpeakerOrder speakerOrder,
            boolean anonymousVoting,
            int roundCount,
            List<TtfTopic> topics
    ) {
        this(
                MVP_STATEMENT_MIN_LENGTH,
                statementMaxLength,
                votingDurationSeconds,
                speakerOrder,
                anonymousVoting,
                roundCount,
                topics
        );
    }

    private static DomainException validation(String message) {
        return new DomainException(DomainException.Code.VALIDATION_ERROR, message);
    }
}
