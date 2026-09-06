package com.toki.ttf.domain.ttf.entity;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

@Getter
@Accessors(fluent = true)
public final class TtfPlayer {
    private final String participantId;
    private final int joinOrder;
    private volatile boolean ready;
    private volatile int score;

    TtfPlayer(String participantId, int joinOrder) {
        this.participantId = Objects.requireNonNull(participantId, "participantId");
        this.joinOrder = joinOrder;
    }

    void markReady() {
        ready = true;
    }

    void addScore(int delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("score delta must not be negative");
        }
        score += delta;
    }
}
