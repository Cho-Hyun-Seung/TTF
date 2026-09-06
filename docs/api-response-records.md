# API response record 명세

- 기준 계약: [`api-spec.md`](api-spec.md)
- 대상 런타임: Java 17, Spring MVC, Jackson
- JSON 필드명: `snake_case`

이 문서는 HTTP response body와 SSE payload의 Java DTO 초안이다. JSON body가 있는 성공·실패 응답은 모두 `ApiResponse<T>` envelope를 사용한다. `204 No Content`와 SSE 스트림에는 JSON envelope를 적용하지 않는다.

## Endpoint와 response record

| Method | Path | 성공 응답 |
|---|---|---|
| `POST` | `/api/v1/rooms` | `201 ApiResponse<CreateRoomResponse>` |
| `GET` | `/api/v1/rooms/by-code/{code}` | `200 ApiResponse<RoomSummaryResponse>` |
| `POST` | `/api/v1/rooms/{room_id}/participants` | `201 ApiResponse<JoinRoomResponse>` |
| `GET` | `/api/v1/games/ttf/{game_id}/snapshot` | `200 ApiResponse<TtfGameSnapshotResponse>` |
| `GET` | `/api/v1/games/ttf/{game_id}/events` | SSE `GameEventResponse` |
| 모든 API | 오류 | `ApiResponse<Void>` |

문장 저장, 투표, 참가자 내보내기와 모든 진행자 command의 성공 응답은 `204 No Content`다.

## 공통 `ApiResponse<T>`

```java
package com.toki.ttf.contract.response;

public class ApiResponse<T> {
    private boolean success;
    private T data;
    private String errorCode;
    private String message;
    private String responseTime;

    public static <T> ApiResponse<T> success(T data) {
        // success=true, data와 response_time 설정
    }

    public static <T> ApiResponse<T> failure(String errorCode, String message) {
        // success=false, error_code, message와 response_time 설정
    }
}
```

null 필드는 직렬화하지 않는다. 동일한 멱등 요청에는 최초 생성된 envelope를 재사용해 `response_time`까지 같은 body를 반환한다.

## 직렬화 및 공개 규칙

```java
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
```

- `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` 또는 동일한 전역 설정을 사용한다.
- 선택 필드는 `@JsonInclude(JsonInclude.Include.NON_NULL)`로 없는 필드를 JSON에서 생략한다.
- 공개 전 `is_fake`, `fake_statement_id`, 득표 수와 voter 정보는 값을 `null`로 채워 보내는 방식도 금지한다. 해당 audience/상태의 DTO 조립 단계에서 필드 자체가 직렬화되지 않아야 한다.
- 서버 response에는 프론트엔드 로컬 값인 `client_received_at_ms`를 넣지 않는다.
- `Instant`는 UTC ISO 8601 문자열로 직렬화한다.

## 공통 `GameReferenceResponse`

```java
package com.toki.ttf.contract.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GameReferenceResponse(
        String id,
        String type
) {}
```

`type`은 현재 `TTF`지만 새 게임 타입이 추가될 수 있으므로 response에서는 확장 가능한 문자열로 둔다.

## `CreateRoomResponse`

```java
package com.toki.ttf.domain.room.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.toki.ttf.contract.response.GameReferenceResponse;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateRoomResponse(
        Room room,
        GameReferenceResponse game
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Room(
            String id,
            String code,
            String joinUrl
    ) {}
}
```

`join_url`에는 방 코드가 포함된 참가 URL만 넣고 세션 또는 진행자 권한 토큰을 넣지 않는다.

## `JoinRoomResponse`

```java
package com.toki.ttf.domain.room.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.toki.ttf.contract.response.GameReferenceResponse;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JoinRoomResponse(
        String roomId,
        String participantId,
        GameReferenceResponse game
) {}
```

진행자와 참가자 세션 토큰은 response body가 아니라 각각 `HttpOnly` 쿠키로 발급한다.

## `RoomSummaryResponse`

```java
package com.toki.ttf.domain.room.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.toki.ttf.contract.response.GameReferenceResponse;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RoomSummaryResponse(
        String id,
        String code,
        String name,
        RoomStatus status,
        boolean joinable,
        int participantCount,
        Settings settings,
        GameReferenceResponse activeGame
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Settings(
            int maxParticipants
    ) {}

    public enum RoomStatus {
        OPEN,
        IN_GAME,
        CLOSED,
        EXPIRED
    }
}
```

`status`는 방 수명 주기만 나타낸다. 게임 단계는 snapshot의 `game.status`로 반환한다.

## `TtfGameSnapshotResponse`

```java
package com.toki.ttf.domain.ttf.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.toki.ttf.domain.room.dto.response.RoomSummaryResponse;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TtfGameSnapshotResponse(
        long version,
        Instant serverTime,
        RoomSummaryResponse room,
        Game game,
        Viewer viewer,
        List<ParticipantSummary> participants,
        List<MyStatement> myStatements,
        RoundSnapshot currentRound,
        List<LeaderboardEntry> leaderboard
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Game(
            String id,
            String type,
            GameStatus status,
            int readyCount,
            int roundCount,
            Integer currentRoundNumber,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            GameStatus pausedFromStatus,
            Settings settings
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Settings(
            int statementMinLength,
            int statementMaxLength,
            int votingDurationSeconds,
            SpeakerOrder speakerOrder,
            boolean anonymousVoting
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Viewer(
            ViewerRole role,
            String participantId,
            String nickname,
            Boolean isReady
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ParticipantSummary(
            String id,
            String nickname,
            ConnectionStatus connectionStatus,
            boolean isReady,
            int score,
            boolean isCurrentSpeaker
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MyStatement(
            String id,
            String content,
            boolean isFake
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoundSnapshot(
            String id,
            int number,
            int total,
            Person speaker,
            List<VisibleStatement> statements,
            Instant votingStartedAt,
            Instant votingEndsAt,
            VoteProgress voteProgress,
            String myVoteStatementId,
            RoundResult result
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Person(
            String id,
            String nickname
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VisibleStatement(
            String id,
            String content,
            int displayOrder
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VoteProgress(
            int completed,
            int eligible
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoundResult(
            String fakeStatementId,
            List<StatementResult> statements,
            int correctVoterCount,
            int fooledParticipantCount,
            List<ScoreChange> scoreChanges
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatementResult(
            String id,
            String content,
            int displayOrder,
            boolean isFake,
            int voteCount,
            double voteRate,
            List<Person> voters
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScoreChange(
            String participantId,
            String nickname,
            int delta,
            int total
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LeaderboardEntry(
            String participantId,
            String nickname,
            int score,
            int rank,
            boolean isMe
    ) {}

    public enum GameStatus {
        LOBBY,
        SUBMISSION,
        READY,
        ROUND_INTRO,
        VOTING,
        VOTE_CLOSED,
        RESULT,
        PAUSED,
        FINISHED,
        CANCELLED
    }

    public enum ViewerRole {
        PARTICIPANT,
        HOST,
        DISPLAY
    }

    public enum SpeakerOrder {
        RANDOM,
        JOIN_ORDER
    }

    public enum ConnectionStatus {
        ONLINE,
        OFFLINE
    }
}
```

### audience별 조립 규칙

| 필드 | participant | host | display |
|---|---:|---:|---:|
| `viewer.participant_id`, `viewer.nickname`, `viewer.is_ready` | O | 생략 | 생략 |
| `my_statements` | 시작 전 본인만 | 생략 | 생략 |
| `participants` | 생략 | O | 생략 |
| `current_round.my_vote_statement_id` | 본인만 | 생략 | 생략 |
| `current_round.result` | `RESULT` 이후 | `RESULT` 이후 | `RESULT` 이후 |
| `result.statements[].voters` | 익명 투표가 꺼진 결과만 | 동일 | 동일 |
| `leaderboard` | `FINISHED` | `FINISHED` | `FINISHED` |

추가 규칙:

- `ROUND_INTRO`, `VOTING`, `VOTE_CLOSED`에는 `RoundResult`를 생성하지 않는다.
- `my_statements[].is_fake`는 인증된 참가자 본인에게만 허용되는 공개 전 예외다.
- `leaderboard[].is_me`는 participant audience의 본인만 `true`이며 host/display에서는 모두 `false`다.
- `vote_rate`는 0~100 범위에서 소수점 한 자리로 계산한다.
- 공동 순위는 경쟁 순위 방식(`1, 1, 3`)을 사용한다.

## `GameEventResponse`

```java
package com.toki.ttf.domain.ttf.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GameEventResponse(
        String eventId,
        String roomId,
        String gameId,
        long version,
        Instant occurredAt
) {}
```

SSE의 `event` 이름은 별도 transport metadata로 전송하며 payload에 중복해서 넣지 않는다. payload에는 원문 문장, `is_fake`, 투표한 statement ID, voter, 세션 토큰을 포함하지 않는다. 수신 클라이언트는 이 payload를 최종 상태로 쓰지 않고 snapshot을 다시 조회한다.

## 구현 체크

- JSON body가 있는 성공·실패 응답은 `ApiResponse<T>`를 사용한다.
- `204 No Content`에는 `{}`, `null`, 성공 메시지 등 어떤 JSON도 반환하지 않는다.
- SSE는 `SseEmitter`와 `GameEventResponse` payload를 직접 사용한다.
- audience별 mapper/assembler를 분리하고 공개 금지 필드가 JSON에 존재하지 않는지 직렬화 계약 테스트를 둔다.
- snapshot의 `version`은 게임 상태에 영향을 주는 쓰기가 커밋될 때 단조 증가한다.
- 쿠키, 세션 토큰과 진행자 권한 정보는 모든 response record에서 제외한다.
