package com.toki.ttf.domain.room.controller;


import com.toki.ttf.domain.room.common.ApiResponse;
import com.toki.ttf.domain.room.dto.request.CreateRoomRequest;
import com.toki.ttf.domain.room.dto.response.CreateRoomResponse;
import com.toki.ttf.domain.room.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rooms")
@RequiredArgsConstructor
public class RoomController {
    private final RoomService roomService;

    @PostMapping()
    public ApiResponse<CreateRoomResponse> createRoom(@RequestBody CreateRoomRequest request) {

        CreateRoomResponse response = roomService.createRoom(request);

        return ApiResponse.success(response);
    }

    @GetMapping("")
    public ApiResponse<Void> getRoom() {
        return ApiResponse.success(null);
    }

    @PostMapping("")
    public ApiResponse<Void> joinRoom() {
        return ApiResponse.success(null);
    }

}
