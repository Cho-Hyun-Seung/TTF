package com.toki.ttf.domain.room.result;

import com.toki.ttf.domain.room.dto.response.CreateRoomResponse;

/**
 * 방 생성 응답과 진행자 쿠키에 기록할 세션 토큰을 함께 전달합니다.
 * 세션 토큰은 API 응답 본문에 포함되지 않습니다.
 */
public record CreatedRoomResult(CreateRoomResponse response, String sessionToken) {
}
