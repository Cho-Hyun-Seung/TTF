package com.toki.ttf.domain.ttf.dto.response;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GameEventResponse(
        String eventId,
        String roomId,
        String gameId,
        long version,
        Instant occurredAt
) {}
