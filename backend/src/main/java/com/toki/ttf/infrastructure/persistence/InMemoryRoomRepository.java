package com.toki.ttf.infrastructure.persistence;

import com.toki.ttf.domain.room.entity.Room;
import com.toki.ttf.domain.room.repository.RoomRepository;
import com.toki.ttf.domain.room.repository.RoomUnavailableException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryRoomRepository implements RoomRepository {
    private final Object indexLock = new Object();
    private final ConcurrentMap<String, Room> roomsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> roomIdByCode = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> roomIdByGameId = new ConcurrentHashMap<>();

    @Override
    public Room createRoom(Room room) {
        Objects.requireNonNull(room, "room");
        synchronized (indexLock) {
            Room existingById = roomsById.get(room.id());
            if (existingById == room) {
                return room;
            }
            if (existingById != null) {
                throw duplicate("room id");
            }
            if (roomIdByCode.containsKey(normalizeCode(room.code()))) {
                throw duplicate("room code");
            }
            if (roomIdByGameId.containsKey(room.activeGame().id())) {
                throw duplicate("game id");
            }

            roomsById.put(room.id(), room);
            roomIdByCode.put(normalizeCode(room.code()), room.id());
            roomIdByGameId.put(room.activeGame().id(), room.id());
            return room;
        }
    }

    @Override
    public Optional<Room> findById(String roomId) {
        if (roomId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(roomsById.get(roomId));
    }

    @Override
    public Optional<Room> findByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String roomId = roomIdByCode.get(normalizeCode(code));
        return roomId == null ? Optional.empty() : findById(roomId);
    }

    @Override
    public Optional<Room> findByGameId(String gameId) {
        if (gameId == null) {
            return Optional.empty();
        }
        String roomId = roomIdByGameId.get(gameId);
        return roomId == null ? Optional.empty() : findById(roomId);
    }

    @Override
    public List<Room> findAll() {
        return List.copyOf(roomsById.values());
    }

    @Override
    public <T> T update(String roomId, RoomMutation<T> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        Room room = Optional.ofNullable(roomsById.get(roomId))
                .orElseThrow(RoomUnavailableException::new);
        synchronized (room) {
            if (roomsById.get(roomId) != room) {
                throw new RoomUnavailableException();
            }
            return mutation.apply(room);
        }
    }

    @Override
    public void deleteById(String roomId) {
        if (roomId == null) {
            return;
        }
        Room room = roomsById.get(roomId);
        if (room == null) {
            return;
        }
        synchronized (room) {
            synchronized (indexLock) {
                if (roomsById.remove(roomId, room)) {
                    roomIdByCode.remove(normalizeCode(room.code()), roomId);
                    roomIdByGameId.remove(room.activeGame().id(), roomId);
                }
            }
        }
    }

    @Override
    public int deleteExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        List<String> expiredRoomIds = new ArrayList<>();
        for (Room room : roomsById.values()) {
            if (room.isDeletionDueAt(now)) {
                expiredRoomIds.add(room.id());
            }
        }
        expiredRoomIds.forEach(this::deleteById);
        return expiredRoomIds.size();
    }

    public int size() {
        return roomsById.size();
    }

    private static String normalizeCode(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }

    private static IllegalStateException duplicate(String key) {
        return new IllegalStateException(key + " already exists");
    }
}
