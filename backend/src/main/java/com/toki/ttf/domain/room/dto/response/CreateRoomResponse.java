package com.toki.ttf.domain.room.dto.response;

import lombok.Builder;

@Builder
public record CreateRoomResponse(
        RoomResponse room,
        GameResponse game
) {
    @Builder
    public record RoomResponse(
            String id,
            String code,
            String joinUrl
    ) {}
    @Builder
    public record GameResponse(
            String id,
            String type
    ) {}

}
