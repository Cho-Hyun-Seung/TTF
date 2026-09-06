package com.toki.ttf.domain.room.service;


import com.toki.ttf.domain.room.dto.request.CreateRoomRequest;
import com.toki.ttf.domain.room.dto.response.CreateRoomResponse;
import org.springframework.stereotype.Service;
import org.apache.commons.lang3.RandomStringUtils;

@Service
public class RoomService {
    private final static int CODE_LENGTH = 10;
    private final static String GAME_ID_PREFIX = "game_";
    private final static String ROOM_ID_PREFIX = "room_";
    private final static String JOIN_URL = "https://game.bright-win.cloud";

    public CreateRoomResponse createRoom(CreateRoomRequest request) {
        String code = RandomStringUtils.randomAlphanumeric(CODE_LENGTH);

        CreateRoomResponse response = CreateRoomResponse.builder()
                .room(CreateRoomResponse.RoomResponse.builder()
                        .id(ROOM_ID_PREFIX + code)
                        .code(code)
                        /* TODO 하드 코딩 바꾸기*/
                        .joinUrl(JOIN_URL + "/join/" + code)
                        .build())
                .game(CreateRoomResponse.GameResponse.builder()
                        .id(GAME_ID_PREFIX + code)
                        .type(request.game().type())
                        .build())
                .build();

        return response;
    }
}
