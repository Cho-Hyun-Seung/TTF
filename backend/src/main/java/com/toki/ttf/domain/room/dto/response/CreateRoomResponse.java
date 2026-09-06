package com.toki.ttf.domain.room.dto.response;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import com.toki.ttf.contract.response.GameReferenceResponse;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateRoomResponse(
        Room room,
        GameReferenceResponse game
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Room(
            String id,
            String code,
            String joinUrl
    ) {}
}
