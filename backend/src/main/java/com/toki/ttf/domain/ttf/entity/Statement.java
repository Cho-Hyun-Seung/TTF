package com.toki.ttf.domain.ttf.entity;

import com.toki.ttf.domain.ttf.constants.TtfTopic;

import java.time.Instant;
import java.util.Objects;

public record Statement(
        String id,
        String gameId,
        String participantId,
        TtfTopic topic,
        String content,
        boolean fake,
        int displayOrder,
        Instant createdAt
) {
    public Statement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(participantId, "participantId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        if (displayOrder < 1 || displayOrder > 3) {
            throw new IllegalArgumentException("displayOrder must be between 1 and 3");
        }
    }
}
