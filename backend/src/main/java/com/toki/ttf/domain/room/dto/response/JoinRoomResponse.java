package com.toki.ttf.domain.room.dto.response;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import com.toki.ttf.contract.response.GameReferenceResponse;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JoinRoomResponse(
        String roomId,
        String participantId,
        GameReferenceResponse game
) {}
