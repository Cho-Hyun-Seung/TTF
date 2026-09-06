package com.toki.ttf.domain.room.dto.request;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateRoomRequest(
        @NotBlank @Size(max = 40) String name,
        @NotNull @Valid RoomSettings settings,
        @NotNull @Valid Game game
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoomSettings(
            @NotNull @Min(2) @Max(100) Integer maxParticipants
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Game(
            @NotNull GameType type,
            @NotNull @Valid TtfSettings settings
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TtfSettings(
            @NotNull @Min(20) @Max(200) Integer statementMaxLength,
            @NotNull @Min(15) @Max(180) Integer votingDurationSeconds,
            @NotNull SpeakerOrder speakerOrder,
            @NotNull Boolean anonymousVoting
    ) {}

    public enum GameType {
        TTF
    }

    public enum SpeakerOrder {
        RANDOM,
        JOIN_ORDER
    }
}
