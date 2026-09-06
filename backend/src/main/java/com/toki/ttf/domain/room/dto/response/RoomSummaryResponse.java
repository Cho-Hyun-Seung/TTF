package com.toki.ttf.domain.room.dto.response;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import com.toki.ttf.contract.response.GameReferenceResponse;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RoomSummaryResponse(
        String id,
        String code,
        String name,
        RoomStatus status,
        boolean joinable,
        int participantCount,
        Settings settings,
        GameReferenceResponse activeGame
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Settings(
            int maxParticipants
    ) {}

    public enum RoomStatus {
        OPEN,
        IN_GAME,
        CLOSED,
        EXPIRED
    }
}
