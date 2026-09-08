package com.toki.ttf;

import com.jayway.jsonpath.JsonPath;
import com.toki.ttf.domain.room.repository.RoomRepository;
import com.toki.ttf.domain.ttf.constants.TtfGameStatus;
import com.toki.ttf.infrastructure.scheduling.VotingScheduler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "ttf.rate-limit.host-commands-per-second=1000"
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TtfApiContractIntegrationTests {

    private static final String HOST_COOKIE = "ttf_host_session";
    private static final String PARTICIPANT_COOKIE = "ttf_participant_session";
    private static final String SAME_ORIGIN = "http://localhost";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private VotingScheduler votingScheduler;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void routingAndMutationGuardFailuresUseHttpErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error_code").value("ROOM_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.response_time").isString())
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(put("/api/v1/rooms"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.response_time").isString());

        mockMvc.perform(write(post("/api/v1/rooms"))
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/rooms")
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRoomBody("untrusted mutation", "JOIN_ORDER", true)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error_code").value("SESSION_REQUIRED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.response_time").isString());
    }

    @Test
    void failedHostCommandIsReplayedAfterTheStateWouldOtherwiseAllowIt() throws Exception {
        CreatedRoom room = createRoom("failure replay", "JOIN_ORDER");
        String startPath = "/api/v1/games/ttf/%s/commands/start".formatted(room.gameId());
        String key = idempotencyKey();

        MvcResult firstFailure = mockMvc.perform(write(post(startPath))
                        .cookie(room.hostSession())
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("NOT_ENOUGH_PARTICIPANTS"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.response_time").isString())
                .andReturn();

        Participant first = join(room, "replay one");
        Participant second = join(room, "replay two");
        saveStatements(room, first, validStatements("replay one"));
        saveStatements(room, second, validStatements("replay two"));
        assertThat(read(getSnapshot(room.gameId(), "host", room.hostSession()),
                "$.data.game.status", String.class)).isEqualTo("READY");

        MvcResult replayedFailure = mockMvc.perform(write(post(startPath))
                        .cookie(room.hostSession())
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("NOT_ENOUGH_PARTICIPANTS"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.response_time").isString())
                .andReturn();

        assertThat(read(replayedFailure, "$.message", String.class))
                .isEqualTo(read(firstFailure, "$.message", String.class));
        assertThat(replayedFailure.getResponse().getContentAsString())
                .isEqualTo(firstFailure.getResponse().getContentAsString());
        assertThat(read(getSnapshot(room.gameId(), "host", room.hostSession()),
                "$.data.game.status", String.class)).isEqualTo("READY");
    }

    @Test
    void threeParticipantsCompleteJoinOrderGameThroughHttpContract() throws Exception {
        CreatedRoom room = createRoom("3명 진진가", "JOIN_ORDER");

        MvcResult lookup = mockMvc.perform(get("/api/v1/rooms/by-code/{code}", room.code().toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(room.roomId()))
                .andExpect(jsonPath("$.data.code").value(room.code()))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.joinable").value(true))
                .andExpect(jsonPath("$.data.participant_count").value(0))
                .andExpect(jsonPath("$.data.settings.max_participants").value(3))
                .andExpect(jsonPath("$.data.active_game.id").value(room.gameId()))
                .andExpect(jsonPath("$.data.active_game.type").value("TTF"))
                .andExpect(jsonPath("$.data.participantCount").doesNotExist())
                .andReturn();
        assertSuccessBody(lookup);
        assertThat(lookup.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");

        Participant first = join(room, "가람");
        Participant second = join(room, "나래");
        Participant third = join(room, "다온");

        saveStatements(room, first, """
                {
                  "statement_sets": [
                    {"topic_id": "TRAVEL", "statements": [
                      {"content": "나는 사막에서 밤을 보낸 적이 있다.", "is_fake": false},
                      {"content": "나는 커피를 한 번도 마신 적이 없다.", "is_fake": true},
                      {"content": "나는 세 개의 악기를 연주할 수 있다.", "is_fake": false}
                    ]}
                  ]
                }
                """);
        saveStatements(room, second, """
                {
                  "statement_sets": [
                    {"topic_id": "TRAVEL", "statements": [
                      {"content": "나는 새벽 기차로 여행한 적이 있다.", "is_fake": false},
                      {"content": "나는 열기구를 직접 조종한 적이 있다.", "is_fake": true},
                      {"content": "나는 겨울 바다에서 수영한 적이 있다.", "is_fake": false}
                    ]}
                  ]
                }
                """);
        saveStatements(room, third, """
                {
                  "statement_sets": [
                    {"topic_id": "TRAVEL", "statements": [
                      {"content": "나는 직접 빵을 구워 본 적이 있다.", "is_fake": false},
                      {"content": "나는 우주 센터에서 일한 적이 있다.", "is_fake": true},
                      {"content": "나는 혼자 제주도를 여행한 적이 있다.", "is_fake": false}
                    ]}
                  ]
                }
                """);

        String firstFake = ownFakeStatementId(room, first);
        String secondFake = ownFakeStatementId(room, second);
        String thirdFake = ownFakeStatementId(room, third);

        MvcResult ready = getSnapshot(room.gameId(), "host", room.hostSession());
        assertThat(read(ready, "$.data.game.status", String.class)).isEqualTo("READY");
        assertThat(read(ready, "$.data.game.ready_count", Number.class).intValue()).isEqualTo(3);

        String startKey = idempotencyKey();
        command("/api/v1/games/ttf/%s/commands/start".formatted(room.gameId()), room.hostSession(), startKey);
        command("/api/v1/games/ttf/%s/commands/start".formatted(room.gameId()), room.hostSession(), startKey);

        playRound(room, 1, first, List.of(second, third), firstFake, true, true);
        command("/api/v1/games/ttf/%s/commands/next-round".formatted(room.gameId()), room.hostSession());

        playRound(room, 2, second, List.of(first, third), secondFake, false, false);
        command("/api/v1/games/ttf/%s/commands/next-round".formatted(room.gameId()), room.hostSession());

        playRound(room, 3, third, List.of(first, second), thirdFake, false, false);
        command("/api/v1/games/ttf/%s/commands/next-round".formatted(room.gameId()), room.hostSession());

        MvcResult finished = getSnapshot(room.gameId(), "display", null);
        assertThat(read(finished, "$.data.game.status", String.class)).isEqualTo("FINISHED");
        assertThat(read(finished, "$.data.room.status", String.class)).isEqualTo("CLOSED");

        List<Map<String, Object>> leaderboard = readList(finished, "$.data.leaderboard");
        assertLeaderboardEntry(leaderboard, first.id(), 0, 3, false);
        assertLeaderboardEntry(leaderboard, second.id(), 3, 1, false);
        assertLeaderboardEntry(leaderboard, third.id(), 3, 1, false);
    }

    @Test
    void validationAndNormalizedNicknameConflictsUseStableErrorEnvelope() throws Exception {
        String invalidCreate = """
                {
                  "name": "   ",
                  "settings": {"max_participants": 101},
                  "game": {
                    "type": "TTF",
                    "settings": {
                      "statement_max_length": 19,
                      "voting_duration_seconds": 14,
                      "speaker_order": "UNKNOWN",
                      "anonymous_voting": true
                    }
                  }
                }
                """;

        mockMvc.perform(write(post("/api/v1/rooms"))
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidCreate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.response_time").isString())
                .andExpect(jsonPath("$.data").doesNotExist());

        CreatedRoom room = createRoom("검증 테스트", "JOIN_ORDER");
        Participant participant = join(room, "민 준");

        mockMvc.perform(write(post("/api/v1/rooms/{roomId}/participants", room.roomId()))
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"  민   준  \"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("NICKNAME_TAKEN"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.response_time").isString());

        join(room, "Straße");
        mockMvc.perform(write(post("/api/v1/rooms/{roomId}/participants", room.roomId()))
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"STRASSE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("NICKNAME_TAKEN"));
        mockMvc.perform(write(post("/api/v1/rooms/{roomId}/participants", room.roomId()))
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"STRAẞE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("NICKNAME_TAKEN"));

        CreatedRoom sigmaRoom = createRoom("유니코드 닉네임", "JOIN_ORDER");
        join(sigmaRoom, "σ");
        mockMvc.perform(write(post("/api/v1/rooms/{roomId}/participants", sigmaRoom.roomId()))
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ς\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("NICKNAME_TAKEN"));

        CreatedRoom dotlessIRoom = createRoom("점 없는 아이 닉네임", "JOIN_ORDER");
        join(dotlessIRoom, "ı");
        join(dotlessIRoom, "i");

        String invalidStatements = """
                {
                  "statement_sets": [
                    {"topic_id": "TRAVEL", "statements": [
                      {"content": "첫 번째 진짜 문장입니다.", "is_fake": false},
                      {"content": "두 번째 진짜 문장입니다.", "is_fake": false},
                      {"content": "세 번째 진짜 문장입니다.", "is_fake": false}
                    ]}
                  ]
                }
                """;

        mockMvc.perform(write(put("/api/v1/games/ttf/{gameId}/participants/me/statements", room.gameId()))
                        .cookie(participant.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidStatements))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"));
    }

    @Test
    void topicCatalogAndSelectedTopicRoundsAreServerAuthoritative() throws Exception {
        MvcResult catalog = mockMvc.perform(get("/api/v1/games/ttf/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].id").value("TRAVEL"))
                .andExpect(jsonPath("$.data[0].title").value("여행"))
                .andExpect(jsonPath("$.data[0].example").isString())
                .andReturn();
        assertThat(catalog.getResponse().getContentAsString())
                .doesNotContain("학교", "직장", "아무도 모르는 경험");

        MvcResult created = performCreateRoom("""
                {
                  "name": "주제 라운드",
                  "settings": {"max_participants": 3},
                  "game": {
                    "type": "TTF",
                    "settings": {
                      "statement_max_length": 100,
                      "voting_duration_seconds": 60,
                      "speaker_order": "JOIN_ORDER",
                      "anonymous_voting": true,
                      "round_count": 2,
                      "topic_ids": ["TRAVEL", "FOOD"]
                    }
                  }
                }
                """, idempotencyKey(), null);
        String gameId = read(created, "$.data.game.id", String.class);
        String roomId = read(created, "$.data.room.id", String.class);
        String code = read(created, "$.data.room.code", String.class);
        Cookie hostSession = requireSessionCookie(created, HOST_COOKIE);
        CreatedRoom topicRoom = new CreatedRoom(roomId, code, gameId, hostSession);

        MvcResult snapshot = getSnapshot(gameId, "host", hostSession);
        assertThat(read(snapshot, "$.game.settings.round_count", Number.class).intValue()).isEqualTo(2);
        assertThat(readStringList(snapshot, "$.game.settings.topics[*].id"))
                .containsExactly("TRAVEL", "FOOD");

        Participant first = join(topicRoom, "첫째");
        Participant second = join(topicRoom, "둘째");
        saveStatements(topicRoom, first, validStatementsForTwoTopics("첫째"));
        saveStatements(topicRoom, second, validStatementsForTwoTopics("둘째"));

        MvcResult participantSnapshot = getSnapshot(gameId, "participant", first.session());
        assertThat(readStringList(participantSnapshot, "$.my_statement_sets[*].topic.id"))
                .containsExactly("TRAVEL", "FOOD");
        command("/api/v1/games/ttf/%s/commands/start".formatted(gameId), hostSession);
        MvcResult started = getSnapshot(gameId, "host", hostSession);
        assertThat(read(started, "$.game.round_count", Number.class).intValue()).isEqualTo(4);
        assertThat(read(started, "$.current_round.topic.id", String.class)).isEqualTo("TRAVEL");

        String mismatchedTopics = """
                {
                  "name": "잘못된 주제 라운드",
                  "settings": {"max_participants": 3},
                  "game": {
                    "type": "TTF",
                    "settings": {
                      "statement_max_length": 100,
                      "voting_duration_seconds": 60,
                      "speaker_order": "JOIN_ORDER",
                      "anonymous_voting": true,
                      "round_count": 2,
                      "topic_ids": ["TRAVEL"]
                    }
                  }
                }
                """;
        mockMvc.perform(write(post("/api/v1/rooms"))
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mismatchedTopics))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"));
    }

    @Test
    void hostAndParticipantPermissionsAreScopedBySessionCookie() throws Exception {
        CreatedRoom room = createRoom("권한 테스트", "JOIN_ORDER");
        Participant participant = join(room, "보라");

        mockMvc.perform(write(post("/api/v1/rooms/{roomId}/commands/cancel", room.roomId()))
                        .cookie(participant.session())
                        .header("Idempotency-Key", idempotencyKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("HOST_PERMISSION_REQUIRED"));

        mockMvc.perform(write(put("/api/v1/games/ttf/{gameId}/participants/me/statements", room.gameId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validStatements("권한 없는")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("SESSION_REQUIRED"));

        mockMvc.perform(write(put("/api/v1/games/ttf/{gameId}/participants/me/statements", room.gameId()))
                        .cookie(room.hostSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validStatements("진행자")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("PARTICIPANT_PERMISSION_REQUIRED"));

        mockMvc.perform(get("/api/v1/games/ttf/{gameId}/snapshot", room.gameId())
                        .param("audience", "participant"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("SESSION_REQUIRED"));
    }

    @Test
    void roomCreationReplaysSameIdempotentRequestAndRejectsChangedBody() throws Exception {
        String key = idempotencyKey();
        String originalBody = createRoomBody("멱등 생성", "JOIN_ORDER", true);

        MvcResult first = performCreateRoom(originalBody, key, null);
        Cookie hostSession = requireSessionCookie(first, HOST_COOKIE);
        String firstResponse = first.getResponse().getContentAsString();

        MvcResult replay = performCreateRoom(originalBody, key, hostSession);
        Map<String, Object> firstData = JsonPath.read(firstResponse, "$.data");
        Map<String, Object> replayData = JsonPath.read(
                replay.getResponse().getContentAsString(), "$.data");
        assertThat(replayData).isEqualTo(firstData);
        assertThat(requireSessionCookie(replay, HOST_COOKIE).getValue()).isEqualTo(hostSession.getValue());

        String changedBody = createRoomBody("다른 방 이름", "JOIN_ORDER", true);
        mockMvc.perform(write(post("/api/v1/rooms"))
                        .cookie(hostSession)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changedBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void hostCommandsReplaySafelyAndCoverPauseResumeExtendSkipAndFinish() throws Exception {
        ReadyRoom ready = readyRoom("진행 명령", true);
        CreatedRoom room = ready.room();

        command("/api/v1/games/ttf/%s/commands/start".formatted(room.gameId()), room.hostSession());
        MvcResult firstIntro = getSnapshot(room.gameId(), "host", room.hostSession());
        assertThat(read(firstIntro, "$.game.status", String.class)).isEqualTo("ROUND_INTRO");

        command("/api/v1/games/ttf/%s/commands/pause".formatted(room.gameId()), room.hostSession());
        MvcResult pausedIntro = getSnapshot(room.gameId(), "host", room.hostSession());
        assertThat(read(pausedIntro, "$.game.status", String.class)).isEqualTo("PAUSED");
        assertThat(read(pausedIntro, "$.game.paused_from_status", String.class)).isEqualTo("ROUND_INTRO");

        command("/api/v1/games/ttf/%s/commands/resume".formatted(room.gameId()), room.hostSession());
        String skippedRoundId = read(getSnapshot(room.gameId(), "host", room.hostSession()),
                "$.current_round.id", String.class);
        command("/api/v1/games/ttf/%s/rounds/%s/commands/skip"
                .formatted(room.gameId(), skippedRoundId), room.hostSession());

        MvcResult secondIntro = getSnapshot(room.gameId(), "host", room.hostSession());
        assertThat(read(secondIntro, "$.game.status", String.class)).isEqualTo("ROUND_INTRO");
        assertThat(read(secondIntro, "$.game.current_round_number", Number.class).intValue()).isEqualTo(2);
        String roundId = read(secondIntro, "$.current_round.id", String.class);

        command("/api/v1/games/ttf/%s/rounds/%s/commands/start-voting"
                .formatted(room.gameId(), roundId), room.hostSession());
        String deadlineBeforeExtension = read(getSnapshot(room.gameId(), "host", room.hostSession()),
                "$.current_round.voting_ends_at", String.class);

        String extendKey = idempotencyKey();
        String extendPath = "/api/v1/games/ttf/%s/rounds/%s/commands/extend-voting"
                .formatted(room.gameId(), roundId);
        commandWithBody(extendPath, room.hostSession(), extendKey, "{\"seconds\":15}");
        String deadlineAfterExtension = read(getSnapshot(room.gameId(), "host", room.hostSession()),
                "$.current_round.voting_ends_at", String.class);
        assertThat(deadlineAfterExtension).isNotEqualTo(deadlineBeforeExtension);

        commandWithBody(extendPath, room.hostSession(), extendKey, "{\"seconds\":15}");
        assertThat(read(getSnapshot(room.gameId(), "host", room.hostSession()),
                "$.current_round.voting_ends_at", String.class)).isEqualTo(deadlineAfterExtension);

        mockMvc.perform(write(post(extendPath))
                        .cookie(room.hostSession())
                        .header("Idempotency-Key", extendKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seconds\":20}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("IDEMPOTENCY_KEY_REUSED"));

        command("/api/v1/games/ttf/%s/commands/pause".formatted(room.gameId()), room.hostSession());
        MvcResult pausedVoting = getSnapshot(room.gameId(), "host", room.hostSession());
        assertThat(read(pausedVoting, "$.game.status", String.class)).isEqualTo("PAUSED");
        assertThat(read(pausedVoting, "$.game.paused_from_status", String.class)).isEqualTo("VOTING");

        command("/api/v1/games/ttf/%s/commands/resume".formatted(room.gameId()), room.hostSession());
        assertThat(read(getSnapshot(room.gameId(), "host", room.hostSession()),
                "$.game.status", String.class)).isEqualTo("VOTING");

        command("/api/v1/games/ttf/%s/rounds/%s/commands/close-voting"
                .formatted(room.gameId(), roundId), room.hostSession());
        String revealPath = "/api/v1/games/ttf/%s/rounds/%s/commands/reveal-result"
                .formatted(room.gameId(), roundId);
        String revealKey = idempotencyKey();
        command(revealPath, room.hostSession(), revealKey);
        command(revealPath, room.hostSession(), revealKey);
        assertThat(read(getSnapshot(room.gameId(), "host", room.hostSession()),
                "$.game.status", String.class)).isEqualTo("RESULT");

        String finishKey = idempotencyKey();
        String finishPath = "/api/v1/games/ttf/%s/commands/finish".formatted(room.gameId());
        command(finishPath, room.hostSession(), finishKey);
        command(finishPath, room.hostSession(), finishKey);
        MvcResult finished = getSnapshot(room.gameId(), "display", null);
        assertThat(read(finished, "$.game.status", String.class)).isEqualTo("FINISHED");
        assertThat(read(finished, "$.room.status", String.class)).isEqualTo("CLOSED");
    }

    @Test
    void participantReconnectKickAndCancelPreserveSessionAndRepeatSemantics() throws Exception {
        CreatedRoom room = createRoom("세션 복구", "JOIN_ORDER");
        Participant first = join(room, "하늘");

        MvcResult rejoin = mockMvc.perform(write(post("/api/v1/rooms/{roomId}/participants", room.roomId()))
                        .cookie(first.session())
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"하늘\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.participant_id").value(first.id()))
                .andReturn();
        assertThat(requireSessionCookie(rejoin, PARTICIPANT_COOKIE).getValue())
                .isEqualTo(first.session().getValue());

        Participant second = join(room, "바다");
        mockMvc.perform(write(delete("/api/v1/rooms/{roomId}/participants/{participantId}",
                                room.roomId(), second.id()))
                        .cookie(first.session())
                        .header("Idempotency-Key", idempotencyKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("HOST_PERMISSION_REQUIRED"));

        String kickKey = idempotencyKey();
        removeParticipant(room, second.id(), kickKey);
        removeParticipant(room, second.id(), kickKey);
        removeParticipant(room, second.id(), idempotencyKey());
        mockMvc.perform(get("/api/v1/rooms/by-code/{code}", room.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participant_count").value(1));

        mockMvc.perform(write(post("/api/v1/rooms/{roomId}/commands/cancel", room.roomId()))
                        .cookie(first.session())
                        .header("Idempotency-Key", idempotencyKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("HOST_PERMISSION_REQUIRED"));

        String cancelKey = idempotencyKey();
        cancelRoom(room, cancelKey);
        cancelRoom(room, cancelKey);
        mockMvc.perform(get("/api/v1/rooms/by-code/{code}", room.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.joinable").value(false));
    }

    @Test
    void startedRoomRejectsNewJoinAndUnknownGameAndRoundAreNotFound() throws Exception {
        ReadyRoom ready = readyRoom("잘못된 식별자", true);
        CreatedRoom room = ready.room();

        mockMvc.perform(get("/api/v1/games/ttf/{gameId}/snapshot", "game_missing")
                        .param("audience", "display"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("GAME_NOT_FOUND"));

        command("/api/v1/games/ttf/%s/commands/start".formatted(room.gameId()), room.hostSession());
        mockMvc.perform(write(post("/api/v1/games/ttf/{gameId}/rounds/{roundId}/commands/start-voting",
                                room.gameId(), "round_missing"))
                        .cookie(room.hostSession())
                        .header("Idempotency-Key", idempotencyKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("ROUND_NOT_FOUND"));

        mockMvc.perform(write(post("/api/v1/games/ttf/{gameId}/rounds/{roundId}/commands/start-voting",
                                room.gameId(), "x".repeat(201)))
                        .cookie(room.hostSession())
                        .header("Idempotency-Key", idempotencyKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"));

        mockMvc.perform(write(post("/api/v1/rooms/{roomId}/participants", room.roomId()))
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"늦은 참가자\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("GAME_ALREADY_STARTED"));

        mockMvc.perform(get("/api/v1/rooms/by-code/{code}", room.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.joinable").value(false));
    }

    @Test
    void statementsRejectNormalizedDuplicatesAndEveryInvalidFakeRatio() throws Exception {
        CreatedRoom room = createRoom("문장 검증", "JOIN_ORDER");
        Participant participant = join(room, "초롱");

        String duplicates = """
                {
                  "statement_sets": [
                    {"topic_id": "TRAVEL", "statements": [
                      {"content": "나는 같은 문장을 작성했습니다.", "is_fake": false},
                      {"content": "  나는   같은 문장을 작성했습니다.  ", "is_fake": true},
                      {"content": "나는 전혀 다른 문장을 작성했습니다.", "is_fake": false}
                    ]}
                  ]
                }
                """;
        saveStatementsExpectValidationError(room, participant, duplicates);

        String twoFakes = """
                {
                  "statement_sets": [
                    {"topic_id": "TRAVEL", "statements": [
                      {"content": "나는 첫 번째 문장을 작성했습니다.", "is_fake": true},
                      {"content": "나는 두 번째 문장을 작성했습니다.", "is_fake": true},
                      {"content": "나는 세 번째 문장을 작성했습니다.", "is_fake": false}
                    ]}
                  ]
                }
                """;
        saveStatementsExpectValidationError(room, participant, twoFakes);

        MvcResult snapshot = getSnapshot(room.gameId(), "participant", participant.session());
        assertThat(read(snapshot, "$.viewer.is_ready", Boolean.class)).isFalse();
        assertThat(readList(snapshot, "$.my_statement_sets[0].statements")).isEmpty();
    }

    @Test
    void nonAnonymousVotingPublishesVotersOnlyAfterResultReveal() throws Exception {
        ReadyRoom ready = readyRoom("공개 투표", false);
        CreatedRoom room = ready.room();
        Participant speaker = ready.first();
        Participant voter = ready.second();
        String fakeStatementId = ownFakeStatementId(room, speaker);

        command("/api/v1/games/ttf/%s/commands/start".formatted(room.gameId()), room.hostSession());
        MvcResult intro = getSnapshot(room.gameId(), "display", null);
        assertPreRevealSafe(intro);
        String roundId = read(intro, "$.current_round.id", String.class);

        command("/api/v1/games/ttf/%s/rounds/%s/commands/start-voting"
                .formatted(room.gameId(), roundId), room.hostSession());
        submitVote(room.gameId(), roundId, voter.session(), fakeStatementId);
        assertPreRevealSafe(getSnapshot(room.gameId(), "display", null));
        assertPreRevealSafe(getSnapshot(room.gameId(), "host", room.hostSession()));

        awaitAutomaticResult(room.gameId());

        MvcResult result = getSnapshot(room.gameId(), "display", null);
        List<Map<String, Object>> statementResults = readList(result, "$.current_round.result.statements");
        Map<String, Object> fakeResult = entryBy(statementResults, "id", fakeStatementId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> voters = (List<Map<String, Object>>) fakeResult.get("voters");
        assertThat(voters).hasSize(1);
        assertThat(voters.get(0))
                .containsEntry("id", voter.id())
                .containsEntry("nickname", voter.nickname());
    }

    @Test
    void anonymousVotingKeepsVotersHiddenAfterResultReveal() throws Exception {
        ReadyRoom ready = readyRoom("비공개 투표", true);
        CreatedRoom room = ready.room();
        Participant speaker = ready.first();
        String fakeStatementId = ownFakeStatementId(room, speaker);

        command("/api/v1/games/ttf/%s/commands/start".formatted(room.gameId()), room.hostSession());
        MvcResult intro = getSnapshot(room.gameId(), "display", null);
        String roundId = read(intro, "$.current_round.id", String.class);
        command("/api/v1/games/ttf/%s/rounds/%s/commands/start-voting"
                .formatted(room.gameId(), roundId), room.hostSession());
        submitVote(room.gameId(), roundId, ready.second().session(), fakeStatementId);

        awaitAutomaticResult(room.gameId());

        MvcResult result = getSnapshot(room.gameId(), "display", null);
        assertThat(read(result, "$.game.status", String.class)).isEqualTo("RESULT");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"voters\"");
    }

    @Test
    void sseUsesRecoveryHeadersAndMinimalSyncRequiredPayloadWithoutWaiting() throws Exception {
        CreatedRoom room = createRoom("SSE 복구", "JOIN_ORDER");
        join(room, "새봄");

        MvcResult events = mockMvc.perform(get("/api/v1/games/ttf/{gameId}/events", room.gameId())
                        .param("audience", "display")
                        .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                        .header("Last-Event-ID", "not-a-sequence"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        assertThat(events.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-cache, no-transform");
        assertThat(events.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        String payload = events.getResponse().getContentAsString();
        assertThat(payload)
                .contains("event:game.sync_required")
                .contains("retry:3000")
                .contains("\"event_id\"")
                .contains("\"room_id\":\"" + room.roomId() + "\"")
                .contains("\"game_id\":\"" + room.gameId() + "\"")
                .contains("\"version\"")
                .contains("\"occurred_at\"")
                .doesNotContain("\"content\"", "\"is_fake\"", "\"statement_id\"", "\"voters\"");

        events.getRequest().getAsyncContext().complete();
    }

    private CreatedRoom createRoom(String name, String speakerOrder) throws Exception {
        return createRoom(name, speakerOrder, true);
    }

    private CreatedRoom createRoom(String name, String speakerOrder, boolean anonymousVoting) throws Exception {
        MvcResult result = performCreateRoom(
                createRoomBody(name, speakerOrder, anonymousVoting),
                idempotencyKey(),
                null
        );

        Cookie hostSession = requireSessionCookie(result, HOST_COOKIE);
        String roomId = read(result, "$.data.room.id", String.class);
        String code = read(result, "$.data.room.code", String.class);
        String joinUrl = read(result, "$.data.room.join_url", String.class);
        String gameId = read(result, "$.data.game.id", String.class);

        assertThat(code).matches("[A-Z0-9]{6}");
        assertThat(joinUrl).endsWith("/join/" + code);
        assertThat(joinUrl).doesNotContain(hostSession.getValue());
        assertThat(roomId).isNotEqualTo(gameId);
        return new CreatedRoom(roomId, code, gameId, hostSession);
    }

    private MvcResult performCreateRoom(String body, String idempotencyKey, Cookie hostSession) throws Exception {
        MockHttpServletRequestBuilder request = write(post("/api/v1/rooms"))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (hostSession != null) {
            request.cookie(hostSession);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.room.id").isString())
                .andExpect(jsonPath("$.data.room.code").isString())
                .andExpect(jsonPath("$.data.room.join_url").isString())
                .andExpect(jsonPath("$.data.room.joinUrl").doesNotExist())
                .andExpect(jsonPath("$.data.game.id").isString())
                .andExpect(jsonPath("$.data.game.type").value("TTF"))
                .andExpect(jsonPath("$.error_code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.response_time").isString())
                .andReturn();
        return result;
    }

    private ReadyRoom readyRoom(String name, boolean anonymousVoting) throws Exception {
        CreatedRoom room = createRoom(name, "JOIN_ORDER", anonymousVoting);
        Participant first = join(room, name + " 첫째");
        Participant second = join(room, name + " 둘째");
        saveStatements(room, first, validStatements("첫째"));
        saveStatements(room, second, validStatements("둘째"));
        MvcResult snapshot = getSnapshot(room.gameId(), "host", room.hostSession());
        assertThat(read(snapshot, "$.game.status", String.class)).isEqualTo("READY");
        return new ReadyRoom(room, first, second);
    }

    private Participant join(CreatedRoom room, String nickname) throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/v1/rooms/{roomId}/participants", room.roomId()))
                        .header("Idempotency-Key", idempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"%s\"}".formatted(nickname)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.room_id").value(room.roomId()))
                .andExpect(jsonPath("$.data.participant_id").isString())
                .andExpect(jsonPath("$.data.game.id").value(room.gameId()))
                .andExpect(jsonPath("$.data.game.type").value("TTF"))
                .andExpect(jsonPath("$.data.roomId").doesNotExist())
                .andExpect(jsonPath("$.response_time").isString())
                .andReturn();

        assertSuccessBody(result);
        Cookie session = requireSessionCookie(result, PARTICIPANT_COOKIE);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(session.getValue());
        return new Participant(read(result, "$.data.participant_id", String.class), nickname, session);
    }

    private void saveStatements(CreatedRoom room, Participant participant, String body) throws Exception {
        mockMvc.perform(write(put("/api/v1/games/ttf/{gameId}/participants/me/statements", room.gameId()))
                        .cookie(participant.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    private void saveStatementsExpectValidationError(
            CreatedRoom room,
            Participant participant,
            String body
    ) throws Exception {
        mockMvc.perform(write(put("/api/v1/games/ttf/{gameId}/participants/me/statements", room.gameId()))
                        .cookie(participant.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("VALIDATION_ERROR"));
    }

    private void removeParticipant(CreatedRoom room, String participantId, String key) throws Exception {
        mockMvc.perform(write(delete("/api/v1/rooms/{roomId}/participants/{participantId}",
                                room.roomId(), participantId))
                        .cookie(room.hostSession())
                        .header("Idempotency-Key", key))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    private void cancelRoom(CreatedRoom room, String key) throws Exception {
        mockMvc.perform(write(post("/api/v1/rooms/{roomId}/commands/cancel", room.roomId()))
                        .cookie(room.hostSession())
                        .header("Idempotency-Key", key))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @SuppressWarnings("unchecked")
    private String ownFakeStatementId(CreatedRoom room, Participant participant) throws Exception {
        MvcResult snapshot = getSnapshot(room.gameId(), "participant", participant.session());
        List<String> fakeIds = readStringList(snapshot,
                "$.my_statement_sets[0].statements[?(@.is_fake == true)].id");
        assertThat(fakeIds).hasSize(1);
        return fakeIds.get(0);
    }

    private void playRound(
            CreatedRoom room,
            int roundNumber,
            Participant speaker,
            List<Participant> voters,
            String knownFakeStatementId,
            boolean voteForFake,
            boolean retryFirstVote
    ) throws Exception {
        MvcResult intro = getSnapshot(room.gameId(), "display", null);
        assertThat(read(intro, "$.game.status", String.class)).isEqualTo("ROUND_INTRO");
        assertThat(read(intro, "$.game.current_round_number", Number.class).intValue()).isEqualTo(roundNumber);
        assertThat(read(intro, "$.current_round.speaker.id", String.class)).isEqualTo(speaker.id());
        assertPreRevealSafe(intro);
        assertPreRevealSafe(getSnapshot(room.gameId(), "host", room.hostSession()));

        String roundId = read(intro, "$.current_round.id", String.class);
        List<String> statementIds = readStringList(intro, "$.current_round.statements[*].id");
        assertThat(statementIds).contains(knownFakeStatementId).hasSize(3);
        String nonFakeStatementId = statementIds.stream()
                .filter(id -> !id.equals(knownFakeStatementId))
                .findFirst()
                .orElseThrow();
        String selectedStatementId = voteForFake ? knownFakeStatementId : nonFakeStatementId;

        command("/api/v1/games/ttf/%s/rounds/%s/commands/start-voting".formatted(room.gameId(), roundId),
                room.hostSession());

        mockMvc.perform(write(put("/api/v1/games/ttf/{gameId}/rounds/{roundId}/vote", room.gameId(), roundId))
                        .cookie(speaker.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statement_id\":\"%s\"}".formatted(selectedStatementId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("SPEAKER_CANNOT_VOTE"));

        submitVote(room.gameId(), roundId, voters.get(0).session(), selectedStatementId);
        if (retryFirstVote) {
            submitVote(room.gameId(), roundId, voters.get(0).session(), selectedStatementId);
        }
        submitVote(room.gameId(), roundId, voters.get(1).session(), selectedStatementId);

        MvcResult voting = getSnapshot(room.gameId(), "participant", voters.get(0).session());
        assertThat(read(voting, "$.game.status", String.class)).isEqualTo("VOTE_CLOSED");
        assertThat(read(voting, "$.current_round.result_reveals_at", String.class)).isNotBlank();
        assertThat(read(voting, "$.current_round.vote_progress.completed", Number.class).intValue()).isEqualTo(2);
        assertThat(read(voting, "$.current_round.vote_progress.eligible", Number.class).intValue()).isEqualTo(2);
        assertThat(read(voting, "$.current_round.my_vote_statement_id", String.class)).isEqualTo(selectedStatementId);
        assertPreRevealSafe(voting);
        assertPreRevealSafe(getSnapshot(room.gameId(), "host", room.hostSession()));
        assertPreRevealSafe(getSnapshot(room.gameId(), "display", null));

        MvcResult closed = getSnapshot(room.gameId(), "display", null);
        assertThat(read(closed, "$.game.status", String.class)).isEqualTo("VOTE_CLOSED");
        assertPreRevealSafe(closed);
        assertPreRevealSafe(getSnapshot(room.gameId(), "host", room.hostSession()));

        mockMvc.perform(write(put("/api/v1/games/ttf/{gameId}/rounds/{roundId}/vote", room.gameId(), roundId))
                        .cookie(voters.get(1).session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statement_id\":\"%s\"}".formatted(selectedStatementId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error_code").value("VOTING_NOT_OPEN"));
        if (roundNumber == 1) {
            mockMvc.perform(write(post(
                                    "/api/v1/games/ttf/{gameId}/rounds/{roundId}/commands/reveal-result",
                                    room.gameId(),
                                    roundId))
                            .cookie(room.hostSession())
                            .header("Idempotency-Key", idempotencyKey()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error_code").value("INVALID_STATE_TRANSITION"));
            mockMvc.perform(write(post(
                                    "/api/v1/games/ttf/{gameId}/rounds/{roundId}/commands/reveal-result",
                                    room.gameId(), roundId))
                            .cookie(voters.get(0).session())
                            .header("Idempotency-Key", idempotencyKey()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error_code").value("HOST_PERMISSION_REQUIRED"));
        }

        awaitAutomaticResult(room.gameId());
        MvcResult result = getSnapshot(room.gameId(), "display", null);
        assertThat(read(result, "$.game.status", String.class)).isEqualTo("RESULT");
        assertThat(read(result, "$.current_round.result.fake_statement_id", String.class))
                .isEqualTo(knownFakeStatementId);
        int expectedCorrect = voteForFake ? 2 : 0;
        int expectedFooled = voters.size() - expectedCorrect;
        assertThat(read(result, "$.current_round.result.correct_voter_count", Number.class).intValue())
                .isEqualTo(expectedCorrect);
        assertThat(read(result, "$.current_round.result.fooled_participant_count", Number.class).intValue())
                .isEqualTo(expectedFooled);

        List<Map<String, Object>> statementResults = readList(result, "$.current_round.result.statements");
        Map<String, Object> fakeResult = entryBy(statementResults, "id", knownFakeStatementId);
        assertThat(number(fakeResult, "vote_count")).isEqualTo(expectedCorrect);
        List<Map<String, Object>> scoreChanges = readList(result, "$.current_round.result.score_changes");
        if (voteForFake) {
            assertThat(scoreChanges).hasSize(expectedCorrect);
            assertThat(scoreChanges)
                    .extracting(change -> (String) change.get("participant_id"))
                    .containsExactlyInAnyOrderElementsOf(voters.stream().map(Participant::id).toList());
            scoreChanges.forEach(change -> assertThat(number(change, "delta")).isEqualTo(1));
        } else {
            assertThat(scoreChanges).hasSize(1);
            assertThat(scoreChanges.get(0).get("participant_id")).isEqualTo(speaker.id());
            assertThat(number(scoreChanges.get(0), "delta")).isEqualTo(expectedFooled);
        }
        MvcResult restored = getSnapshot(room.gameId(), "participant", voters.get(0).session());
        assertThat(read(restored, "$.game.status", String.class)).isEqualTo("RESULT");
        assertThat(read(restored, "$.version", Number.class))
                .isEqualTo(read(result, "$.version", Number.class));
    }

    @Test
    void automaticRevealIsRescheduledAfterPauseAndResume() throws Exception {
        ReadyRoom ready = readyRoom("공개 대기 정지", true);
        CreatedRoom room = ready.room();
        command("/api/v1/games/ttf/%s/commands/start".formatted(room.gameId()), room.hostSession());
        var game = roomRepository.findByGameId(room.gameId()).orElseThrow().activeGame();
        var round = game.currentRound().orElseThrow();
        command("/api/v1/games/ttf/%s/rounds/%s/commands/start-voting"
                .formatted(room.gameId(), round.id()), room.hostSession());
        submitVote(room.gameId(), round.id(), ready.second().session(), round.statements().get(0).id());
        command("/api/v1/games/ttf/%s/commands/pause".formatted(room.gameId()), room.hostSession());

        Thread.sleep(2_100);
        MvcResult paused = getSnapshot(room.gameId(), "participant", ready.second().session());
        assertThat(read(paused, "$.game.status", String.class)).isEqualTo("PAUSED");
        assertPreRevealSafe(paused);
        command("/api/v1/games/ttf/%s/commands/resume".formatted(room.gameId()), room.hostSession());
        assertThat(game.status()).isEqualTo(TtfGameStatus.VOTE_CLOSED);
        awaitAutomaticResult(room.gameId());
        assertThat(round.result()).isPresent();
    }

    @Test
    void reconnectSnapshotRevealsOverdueResultIfScheduledTaskWasMissed() throws Exception {
        ReadyRoom ready = readyRoom("공개 재접속", true);
        CreatedRoom room = ready.room();
        command("/api/v1/games/ttf/%s/commands/start".formatted(room.gameId()), room.hostSession());
        var game = roomRepository.findByGameId(room.gameId()).orElseThrow().activeGame();
        var round = game.currentRound().orElseThrow();
        command("/api/v1/games/ttf/%s/rounds/%s/commands/start-voting"
                .formatted(room.gameId(), round.id()), room.hostSession());
        submitVote(room.gameId(), round.id(), ready.second().session(), round.statements().get(0).id());
        votingScheduler.cancel(room.gameId(), game.version());

        Thread.sleep(2_100);
        assertThat(game.status()).isEqualTo(TtfGameStatus.VOTE_CLOSED);
        MvcResult restored = getSnapshot(room.gameId(), "participant", ready.second().session());
        assertThat(read(restored, "$.game.status", String.class)).isEqualTo("RESULT");
        long revealedVersion = game.version();
        getSnapshot(room.gameId(), "host", room.hostSession());
        assertThat(game.version()).isEqualTo(revealedVersion);
    }

    private void awaitAutomaticResult(String gameId) throws InterruptedException {
        // Inspect domain state so snapshot catch-up cannot hide a broken server schedule.
        var game = roomRepository.findByGameId(gameId).orElseThrow().activeGame();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (game.status() != TtfGameStatus.RESULT && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(game.status()).isEqualTo(TtfGameStatus.RESULT);
    }

    private void submitVote(String gameId, String roundId, Cookie participantSession, String statementId)
            throws Exception {
        mockMvc.perform(write(put("/api/v1/games/ttf/{gameId}/rounds/{roundId}/vote", gameId, roundId))
                        .cookie(participantSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statement_id\":\"%s\"}".formatted(statementId)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    private MvcResult getSnapshot(String gameId, String audience, Cookie session) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/games/ttf/{gameId}/snapshot", gameId)
                .param("audience", audience);
        if (session != null) {
            request.cookie(session);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.version").isNumber())
                .andExpect(jsonPath("$.data.server_time").isString())
                .andExpect(jsonPath("$.data.client_received_at_ms").doesNotExist())
                .andExpect(jsonPath("$.response_time").isString())
                .andReturn();
        assertSuccessBody(result);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store");
        return result;
    }

    private void command(String path, Cookie hostSession) throws Exception {
        command(path, hostSession, idempotencyKey());
    }

    private void command(String path, Cookie hostSession, String idempotencyKey) throws Exception {
        mockMvc.perform(write(post(path))
                        .cookie(hostSession)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    private void commandWithBody(
            String path,
            Cookie hostSession,
            String idempotencyKey,
            String body
    ) throws Exception {
        mockMvc.perform(write(post(path))
                        .cookie(hostSession)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    private Cookie requireSessionCookie(MvcResult result, String cookieName) {
        String setCookie = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(cookieName + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Set-Cookie header for " + cookieName));
        assertThat(setCookie)
                .contains("Path=/api/v1")
                .contains("HttpOnly")
                .contains("SameSite=Lax");

        String value = setCookie.substring((cookieName + "=").length(), setCookie.indexOf(';'));
        assertThat(value).isNotBlank();
        return new Cookie(cookieName, value);
    }

    @SuppressWarnings("unchecked")
    private void assertSuccessBody(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        Map<String, Object> root = JsonPath.parse(body).read("$", Map.class);
        assertThat(root)
                .containsEntry("success", true)
                .containsKeys("data", "response_time")
                .doesNotContainKeys("error_code", "message");
    }

    private void assertPreRevealSafe(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(
                "\"is_fake\"",
                "\"fake_statement_id\"",
                "\"vote_count\"",
                "\"voters\""
        );
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request
                .header(HttpHeaders.ORIGIN, SAME_ORIGIN)
                .header("Sec-Fetch-Site", "same-origin");
    }

    private static String idempotencyKey() {
        return UUID.randomUUID().toString();
    }

    private static String validStatements(String prefix) {
        return """
                {
                  "statement_sets": [
                    {"topic_id": "TRAVEL", "statements": [
                      {"content": "%s 첫 번째 진짜 문장입니다.", "is_fake": false},
                      {"content": "%s 두 번째 가짜 문장입니다.", "is_fake": true},
                      {"content": "%s 세 번째 진짜 문장입니다.", "is_fake": false}
                    ]}
                  ]
                }
                """.formatted(prefix, prefix, prefix);
    }

    private static String validStatementsForTwoTopics(String prefix) {
        return """
                {
                  "statement_sets": [
                    {"topic_id": "TRAVEL", "statements": [
                      {"content": "%s 여행 첫 번째 진짜 문장입니다.", "is_fake": false},
                      {"content": "%s 여행 두 번째 가짜 문장입니다.", "is_fake": true},
                      {"content": "%s 여행 세 번째 진짜 문장입니다.", "is_fake": false}
                    ]},
                    {"topic_id": "FOOD", "statements": [
                      {"content": "%s 음식 첫 번째 진짜 문장입니다.", "is_fake": false},
                      {"content": "%s 음식 두 번째 가짜 문장입니다.", "is_fake": true},
                      {"content": "%s 음식 세 번째 진짜 문장입니다.", "is_fake": false}
                    ]}
                  ]
                }
                """.formatted(prefix, prefix, prefix, prefix, prefix, prefix);
    }

    private static String createRoomBody(String name, String speakerOrder, boolean anonymousVoting) {
        return """
                {
                  "name": "%s",
                  "settings": {"max_participants": 3},
                  "game": {
                    "type": "TTF",
                    "settings": {
                      "statement_max_length": 100,
                      "voting_duration_seconds": 60,
                      "speaker_order": "%s",
                      "anonymous_voting": %s,
                      "round_count": 1,
                      "topic_ids": ["TRAVEL"]
                    }
                  }
                }
                """.formatted(name, speakerOrder, anonymousVoting);
    }

    private static <T> T read(MvcResult result, String path, Class<T> type) throws Exception {
        String body = result.getResponse().getContentAsString();
        return JsonPath.parse(body).read(responsePath(body, path), type);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readList(MvcResult result, String path) throws Exception {
        String body = result.getResponse().getContentAsString();
        return JsonPath.parse(body).read(responsePath(body, path), List.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> readStringList(MvcResult result, String path) throws Exception {
        String body = result.getResponse().getContentAsString();
        return JsonPath.parse(body).read(responsePath(body, path), List.class);
    }

    private static String responsePath(String body, String path) {
        if (path.startsWith("$.data")) {
            return path;
        }
        Boolean success = JsonPath.read(body, "$.success");
        return Boolean.TRUE.equals(success) ? "$.data" + path.substring(1) : path;
    }

    private static Map<String, Object> entryBy(List<Map<String, Object>> entries, String key, String value) {
        return entries.stream()
                .filter(entry -> value.equals(entry.get(key)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing entry with " + key + "=" + value));
    }

    private static int number(Map<String, Object> entry, String key) {
        return ((Number) entry.get(key)).intValue();
    }

    private static void assertLeaderboardEntry(
            List<Map<String, Object>> leaderboard,
            String participantId,
            int expectedScore,
            int expectedRank,
            boolean expectedIsMe
    ) {
        Map<String, Object> entry = entryBy(leaderboard, "participant_id", participantId);
        assertThat(number(entry, "score")).isEqualTo(expectedScore);
        assertThat(number(entry, "rank")).isEqualTo(expectedRank);
        assertThat(entry.get("is_me")).isEqualTo(expectedIsMe);
    }

    private record CreatedRoom(String roomId, String code, String gameId, Cookie hostSession) {}

    private record Participant(String id, String nickname, Cookie session) {}

    private record ReadyRoom(CreatedRoom room, Participant first, Participant second) {}
}
