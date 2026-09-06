package com.toki.ttf.domain.room.entity;

import com.toki.ttf.domain.room.constants.ConnectionStatus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Objects;

@Getter
@Accessors(fluent = true)
public final class Participant {
    private final String id;
    private final String nickname;
    @Getter(AccessLevel.PACKAGE)
    private final String normalizedNickname;
    private final Instant joinedAt;
    private ConnectionStatus connectionStatus;

    Participant(String id, String nickname, String normalizedNickname, Instant joinedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.nickname = Objects.requireNonNull(nickname, "nickname");
        this.normalizedNickname = Objects.requireNonNull(normalizedNickname, "normalizedNickname");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        this.connectionStatus = ConnectionStatus.ONLINE;
    }

    public synchronized ConnectionStatus connectionStatus() {
        return connectionStatus;
    }

    synchronized boolean changeConnectionStatus(ConnectionStatus next) {
        Objects.requireNonNull(next, "next");
        if (connectionStatus == next) {
            return false;
        }
        connectionStatus = next;
        return true;
    }
}
