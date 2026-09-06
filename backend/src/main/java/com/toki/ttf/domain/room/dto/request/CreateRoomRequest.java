package com.toki.ttf.domain.room.dto.request;

public record CreateRoomRequest(
        String name,
        CreateRoomSettings settings,
        CreateRoomGame game
) {
    public record CreateRoomSettings(
            Integer maxPlayers
    ){}

    public record CreateRoomGame(
            String type
    ){
        public record CreateRoomGameSettings(
                Integer statementMaxLength,
                Integer votingDurationSeconds,
                String speakerOrder,
                String anonymousVoting
        ){}
    }
}
