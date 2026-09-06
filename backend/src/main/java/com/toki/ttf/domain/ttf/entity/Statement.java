package com.toki.ttf.domain.ttf.entity;

import java.time.Instant;
import java.util.Objects;

public record Statement(
        String id,
        String gameId,
        String participantId,
        String content,
        boolean fake,
        int displayOrder,
        Instant createdAt
) {
    public Statement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(participantId, "participantId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        if (displayOrder < 1 || displayOrder > 3) {
            throw new IllegalArgumentException("displayOrder must be between 1 and 3");
        }
    }
}
