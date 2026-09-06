package com.toki.ttf.domain.room.controller;

import com.toki.ttf.contract.response.ApiResponse;
import com.toki.ttf.domain.room.dto.request.CreateRoomRequest;
import com.toki.ttf.domain.room.dto.request.JoinRoomRequest;
import com.toki.ttf.domain.room.dto.response.CreateRoomResponse;
import com.toki.ttf.domain.room.dto.response.JoinRoomResponse;
import com.toki.ttf.domain.room.dto.response.RoomSummaryResponse;
import com.toki.ttf.domain.room.result.CreatedRoomResult;
import com.toki.ttf.domain.room.result.JoinedRoomResult;
import com.toki.ttf.domain.room.service.RoomService;
import com.toki.ttf.infrastructure.security.RequestSecurity;
import com.toki.ttf.infrastructure.security.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 방 생성, 조회, 참가와 로비 관리 요청을 처리하는 HTTP 진입점이다.
 * 도메인 처리와 응답 DTO 생성은 {@link RoomService}에 위임하고 공통 API 응답 래핑,
 * 쿠키, 상태 코드, 캐시 헤더를 다룬다.
 */
@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final SessionService sessionService;
    private final RequestSecurity requestSecurity;

    /**
     * 방과 활성 게임을 생성하고 생성자에게 진행자 세션 쿠키를 발급한다.
     *
     * @return 생성된 방과 게임 정보
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreateRoomResponse> createRoom(
            @Valid @RequestBody CreateRoomRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @CookieValue(name = SessionService.HOST_COOKIE, required = false) String hostToken,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        requestSecurity.requireTrustedMutation(servletRequest);
        CreatedRoomResult created = roomService.createRoom(
                request,
                idempotencyKey,
                hostToken,
                clientAddress(servletRequest)
        );
        servletResponse.addHeader(
                HttpHeaders.SET_COOKIE,
                sessionService.hostCookie(created.sessionToken()).toString()
        );
        return ApiResponse.success(created.response());
    }

    /**
     * 6자리 방 코드로 입장 전 공개 가능한 방 요약을 조회한다.
     *
     * @return 방 요약 정보
     */
    @GetMapping("/by-code/{code}")
    public ApiResponse<RoomSummaryResponse> getRoomByCode(
            @PathVariable String code,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        servletResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.success(roomService.getRoomByCode(code, clientAddress(servletRequest)));
    }

    /**
     * 게임 시작 전 방에 참가자를 등록하고 참가자 세션 쿠키를 발급한다.
     *
     * @return 참가자와 활성 게임 식별 정보
     */
    @PostMapping("/{roomId}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JoinRoomResponse> joinRoom(
            @PathVariable String roomId,
            @Valid @RequestBody JoinRoomRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        requestSecurity.requireTrustedMutation(servletRequest);
        JoinedRoomResult joined = roomService.joinRoom(
                roomId,
                request,
                idempotencyKey,
                participantToken,
                clientAddress(servletRequest)
        );
        servletResponse.addHeader(
                HttpHeaders.SET_COOKIE,
                sessionService.participantCookie(joined.sessionToken()).toString()
        );
        return ApiResponse.success(joined.response());
    }

    /**
     * 진행자 권한으로 로비 참가자를 내보낸다.
     */
    @DeleteMapping("/{roomId}/participants/{participantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeParticipant(
            @PathVariable String roomId,
            @PathVariable String participantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @CookieValue(name = SessionService.HOST_COOKIE, required = false) String hostToken,
            @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
            HttpServletRequest servletRequest
    ) {
        requestSecurity.requireTrustedMutation(servletRequest);
        roomService.removeParticipant(roomId, participantId, idempotencyKey, hostToken, participantToken);
    }

    /**
     * 진행자 권한으로 시작 전 방과 활성 게임을 취소한다.
     */
    @PostMapping("/{roomId}/commands/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelRoom(
            @PathVariable String roomId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @CookieValue(name = SessionService.HOST_COOKIE, required = false) String hostToken,
            @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
            HttpServletRequest servletRequest
    ) {
        requestSecurity.requireTrustedMutation(servletRequest);
        roomService.cancelRoom(roomId, idempotencyKey, hostToken, participantToken);
    }

    private static String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
