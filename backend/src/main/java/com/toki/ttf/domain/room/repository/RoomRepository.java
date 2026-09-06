package com.toki.ttf.domain.room.repository;

import com.toki.ttf.domain.room.entity.Room;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RoomRepository {
    Room createRoom(Room room);

    Optional<Room> findById(String roomId);

    Optional<Room> findByCode(String code);

    Optional<Room> findByGameId(String gameId);

    List<Room> findAll();

    <T> T update(String roomId, RoomMutation<T> mutation);

    void deleteById(String roomId);

    int deleteExpired(Instant now);

    @FunctionalInterface
    interface RoomMutation<T> {
        T apply(Room room);
    }
}
