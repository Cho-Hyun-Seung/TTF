package com.toki.ttf.domain.ttf.entity;

import java.time.Instant;
import java.util.Objects;

public record Vote(
        String id,
        String roundId,
        String voterParticipantId,
        String statementId,
        Instant createdAt,
        Instant updatedAt
) {
    public Vote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(voterParticipantId, "voterParticipantId");
        Objects.requireNonNull(statementId, "statementId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    Vote changeTo(String nextStatementId, Instant now) {
        return new Vote(id, roundId, voterParticipantId, nextStatementId, createdAt, now);
    }
}
