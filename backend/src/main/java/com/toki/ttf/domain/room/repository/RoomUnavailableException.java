package com.toki.ttf.domain.room.repository;

/** Raised when a room disappears between authorization and an atomic mutation. */
public final class RoomUnavailableException extends RuntimeException {
    public RoomUnavailableException() {
        super("room is no longer available");
    }
}
