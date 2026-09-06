package com.toki.ttf.domain.ttf.controller;

import com.toki.ttf.contract.response.ApiResponse;
import com.toki.ttf.domain.ttf.dto.request.ExtendVotingRequest;
import com.toki.ttf.domain.ttf.dto.request.SaveStatementsRequest;
import com.toki.ttf.domain.ttf.dto.request.SubmitVoteRequest;
import com.toki.ttf.domain.ttf.dto.response.TtfGameSnapshotResponse;
import com.toki.ttf.domain.ttf.service.TtfGameService;
import com.toki.ttf.infrastructure.security.RequestSecurity;
import com.toki.ttf.infrastructure.security.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * TTF 게임의 상태 조회, 실시간 이벤트 연결, 참가자 입력과 진행자 명령을 노출한다.
 * 요청 검증과 공통 API 응답 래핑 등 HTTP 전송 책임만 가지며 게임 규칙과 응답 DTO 생성은
 * {@link TtfGameService}에 위임한다.
 */
@RestController
@RequestMapping("/api/v1/games/ttf/{gameId}")
@RequiredArgsConstructor
public class TtfGameController {

    private final TtfGameService gameService;
    private final RequestSecurity requestSecurity;

    /**
     * audience 권한에 맞는 서버 권위 게임 스냅샷을 조회한다.
     *
     * @return 요청 대상의 공개 범위가 적용된 현재 게임 상태
     */
    @GetMapping("/snapshot")
    public ApiResponse<TtfGameSnapshotResponse> snapshot(
            @PathVariable String gameId,
            @RequestParam String audience,
            @CookieValue(name = SessionService.HOST_COOKIE, required = false) String hostToken,
            @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
            HttpServletResponse servletResponse
    ) {
        servletResponse.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        servletResponse.setHeader(HttpHeaders.VARY, HttpHeaders.COOKIE);
        return ApiResponse.success(gameService.snapshot(gameId, audience, hostToken, participantToken));
    }

    /**
     * 상태 변경 알림과 재동기화 신호를 수신할 SSE 연결을 연다.
     *
     * @return 연결 수명 동안 유지되는 SSE emitter
     */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String gameId,
            @RequestParam String audience,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @CookieValue(name = SessionService.HOST_COOKIE, required = false) String hostToken,
            @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        SseEmitter emitter = gameService.events(
                gameId,
                audience,
                hostToken,
                participantToken,
                lastEventId,
                clientAddress(servletRequest)
        );
        servletResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        servletResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        servletResponse.setHeader("X-Accel-Buffering", "no");
        return emitter;
    }

    /**
     * 인증된 참가자의 진진가 문장 세 개를 저장하거나 교체한다.
     */
    @PutMapping("/participants/me/statements")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveStatements(
            @PathVariable String gameId,
            @Valid @RequestBody SaveStatementsRequest request,
            @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
            @CookieValue(name = SessionService.HOST_COOKIE, required = false) String hostToken,
            HttpServletRequest servletRequest
    ) {
        requestSecurity.requireTrustedMutation(servletRequest);
        gameService.saveStatements(gameId, request, participantToken, hostToken);
    }

    /**
     * 인증된 참가자의 라운드 투표를 등록하거나 변경한다.
     */
    @PutMapping("/rounds/{roundId}/vote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitVote(
            @PathVariable String gameId,
            @PathVariable String roundId,
            @Valid @RequestBody SubmitVoteRequest request,
            @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
            @CookieValue(name = SessionService.HOST_COOKIE, required = false) String hostToken,
            HttpServletRequest servletRequest
    ) {
        requestSecurity.requireTrustedMutation(servletRequest);
        gameService.submitVote(gameId, roundId, request, participantToken, hostToken);
    }

    /**
     * 준비된 로비의 게임을 시작한다.
     */
    @PostMapping("/commands/start")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void start(@PathVariable String gameId,
                                      @RequestHeader("Idempotency-Key") String key,
                                      @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                      @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                      HttpServletRequest request) {
        mutation(request);
        gameService.start(gameId, key, token, participantToken);
    }

    /**
     * 현재 라운드의 투표를 시작하고 서버 마감 작업을 예약한다.
     */
    @PostMapping("/rounds/{roundId}/commands/start-voting")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void startVoting(@PathVariable String gameId,
                                            @PathVariable String roundId,
                                            @RequestHeader("Idempotency-Key") String key,
                                            @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                            @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                            HttpServletRequest request) {
        mutation(request);
        gameService.startVoting(gameId, roundId, key, token, participantToken);
    }

    /**
     * 진행 중인 투표의 서버 마감 시각을 연장한다.
     */
    @PostMapping("/rounds/{roundId}/commands/extend-voting")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void extendVoting(@PathVariable String gameId,
                                             @PathVariable String roundId,
                                             @Valid @RequestBody ExtendVotingRequest body,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                             @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                             HttpServletRequest request) {
        mutation(request);
        gameService.extendVoting(gameId, roundId, body, key, token, participantToken);
    }

    /**
     * 현재 라운드의 투표를 즉시 마감한다.
     */
    @PostMapping("/rounds/{roundId}/commands/close-voting")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeVoting(@PathVariable String gameId,
                                            @PathVariable String roundId,
                                            @RequestHeader("Idempotency-Key") String key,
                                            @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                            @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                            HttpServletRequest request) {
        mutation(request);
        gameService.closeVoting(gameId, roundId, key, token, participantToken);
    }

    /**
     * 마감된 라운드 결과와 점수 변동을 공개한다.
     */
    @PostMapping("/rounds/{roundId}/commands/reveal-result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revealResult(@PathVariable String gameId,
                                             @PathVariable String roundId,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                             @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                             HttpServletRequest request) {
        mutation(request);
        gameService.revealResult(gameId, roundId, key, token, participantToken);
    }

    /**
     * 현재 라운드를 점수 반영 없이 건너뛴다.
     */
    @PostMapping("/rounds/{roundId}/commands/skip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skipRound(@PathVariable String gameId,
                                          @PathVariable String roundId,
                                          @RequestHeader("Idempotency-Key") String key,
                                          @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                          @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                          HttpServletRequest request) {
        mutation(request);
        gameService.skipRound(gameId, roundId, key, token, participantToken);
    }

    /**
     * 다음 발표자 라운드로 전환하거나 마지막 라운드 뒤 게임을 종료한다.
     */
    @PostMapping("/commands/next-round")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void nextRound(@PathVariable String gameId,
                                          @RequestHeader("Idempotency-Key") String key,
                                          @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                          @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                          HttpServletRequest request) {
        mutation(request);
        gameService.nextRound(gameId, key, token, participantToken);
    }

    /**
     * 현재 게임 진행 상태와 투표 잔여 시간을 보존하고 일시 정지한다.
     */
    @PostMapping("/commands/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(@PathVariable String gameId,
                                      @RequestHeader("Idempotency-Key") String key,
                                      @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                      @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                      HttpServletRequest request) {
        mutation(request);
        gameService.pause(gameId, key, token, participantToken);
    }

    /**
     * 일시 정지 전 상태로 게임을 재개한다.
     */
    @PostMapping("/commands/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(@PathVariable String gameId,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                       @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                       HttpServletRequest request) {
        mutation(request);
        gameService.resume(gameId, key, token, participantToken);
    }

    /**
     * 공개가 끝난 점수만 유지한 채 진행 중 게임을 조기 종료한다.
     */
    @PostMapping("/commands/finish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void finish(@PathVariable String gameId,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @CookieValue(name = SessionService.HOST_COOKIE, required = false) String token,
                                       @CookieValue(name = SessionService.PARTICIPANT_COOKIE, required = false) String participantToken,
                                       HttpServletRequest request) {
        mutation(request);
        gameService.finish(gameId, key, token, participantToken);
    }

    private void mutation(HttpServletRequest request) {
        requestSecurity.requireTrustedMutation(request);
    }

    private static String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
