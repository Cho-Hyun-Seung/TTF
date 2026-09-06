# API request record 명세

- 기준 계약: [`api-spec.md`](api-spec.md)
- 대상 런타임: Java 17, Spring MVC, Jackson
- JSON 필드명: `snake_case`

이 문서는 HTTP request body를 받는 API의 Java `record` 초안이다. 각 최상위 `record`는 구현할 때 같은 이름의 `.java` 파일로 분리한다. path variable, query parameter, 쿠키, `Idempotency-Key`는 request body가 아니므로 이 문서의 record에 넣지 않는다.

## Endpoint와 request record

| Method | Path | Request record |
|---|---|---|
| `POST` | `/api/v1/rooms` | `CreateRoomRequest` |
| `POST` | `/api/v1/rooms/{room_id}/participants` | `JoinRoomRequest` |
| `PUT` | `/api/v1/games/ttf/{game_id}/participants/me/statements` | `SaveStatementsRequest` |
| `PUT` | `/api/v1/games/ttf/{game_id}/rounds/{round_id}/vote` | `SubmitVoteRequest` |
| `POST` | `/api/v1/games/ttf/{game_id}/rounds/{round_id}/commands/extend-voting` | `ExtendVotingRequest` |

나머지 `DELETE` 및 진행자 command API는 request body가 없다.

## 공통 직렬화 규칙

아래 예시는 record마다 `@JsonNaming`을 선언한다. 프로젝트 전역 `ObjectMapper`에 `SNAKE_CASE`가 설정되면 중복 annotation은 제거할 수 있다.

```java
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
```

클라이언트 입력은 nullable wrapper 타입으로 받은 뒤 Bean Validation과 도메인 검증을 모두 수행한다. primitive를 사용하면 누락된 값과 `false` 또는 `0`을 구분할 수 없다.

예시의 `jakarta.validation` annotation을 실제 코드에 사용할 경우 현재 `build.gradle`에는 없는 Bean Validation 지원 여부를 먼저 확인한다. 새 의존성을 추가하지 않는다면 같은 제약을 기존 서버 검증 계층에서 구현하되 Controller의 형식 검증과 서비스/도메인의 상태·권한 검증을 생략하지 않는다.

## `CreateRoomRequest`

```java
package com.toki.ttf.domain.room.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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
```

추가 도메인 검증:

- `name`은 trim 후 1~40자여야 한다.
- `statement_min_length`는 MVP에서 5자로 고정하고 요청으로 받지 않는다.
- 현재 지원하는 `game.type`은 `TTF`뿐이다.
- 방과 활성 게임은 한 트랜잭션에서 함께 생성한다.

## `JoinRoomRequest`

```java
package com.toki.ttf.domain.room.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JoinRoomRequest(
        @NotBlank @Size(max = 20) String nickname
) {}
```

추가 도메인 검증:

- 닉네임은 trim 후 1~20자여야 한다.
- 비교할 때 앞뒤 공백 제거, 연속 공백 축약, Unicode 정규화와 locale-safe case folding을 적용한다.
- 정원 확인과 닉네임 선점은 원자적으로 처리한다.
- 방이 `OPEN`이고 게임 시작 전일 때만 참가를 허용한다.

## `SaveStatementsRequest`

```java
package com.toki.ttf.domain.ttf.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SaveStatementsRequest(
        @NotNull @Size(min = 3, max = 3)
        List<@Valid Statement> statements
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Statement(
            @NotBlank String content,
            @NotNull Boolean isFake
    ) {}
}
```

추가 도메인 검증:

- 문장은 정확히 3개이고 `is_fake=true`는 정확히 1개여야 한다.
- 각 `content`는 trim 후 해당 게임의 `statement_min_length`와 `statement_max_length`를 만족해야 한다.
- 연속 공백 축약 및 Unicode 정규화 후 중복 문장이 없어야 한다.
- `LOBBY`, `SUBMISSION`, `READY`에서만 전체 교체를 허용한다.
- 자동 금칙어 필터나 진위 판정은 적용하지 않는다.

## `SubmitVoteRequest`

```java
package com.toki.ttf.domain.ttf.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubmitVoteRequest(
        @NotBlank String statementId
) {}
```

추가 도메인 검증:

- voter는 참가자 세션에서 결정하며 request body로 받지 않는다.
- `statement_id`는 현재 라운드에 속해야 한다.
- 발표자는 투표할 수 없고, 서버가 아직 `VOTING` 상태이며 마감 시각 전이어야 한다.
- `(round_id, voter_participant_id)`를 유일하게 유지해 재요청과 투표 변경을 멱등적으로 처리한다.

## `ExtendVotingRequest`

```java
package com.toki.ttf.domain.ttf.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExtendVotingRequest(
        @NotNull @Min(5) @Max(60) Integer seconds
) {}
```

`seconds`는 현재 서버의 `voting_ends_at`에 더하며, 상태가 `VOTING`일 때만 허용한다.

## body가 없는 요청

다음 요청은 body record를 만들지 않는다.

- `GET /rooms/by-code/{code}`: `code`는 path variable이다.
- `GET /games/ttf/{game_id}/snapshot`: `audience`는 query parameter다.
- `GET /games/ttf/{game_id}/events`: `audience`는 query parameter이며 `Last-Event-ID`는 header다.
- 참가자 내보내기, 방 취소, 게임 시작, 투표 시작/마감, 결과 공개, 라운드 건너뛰기, 다음 라운드, 일시 정지/재개, 조기 종료는 body가 없다.

## 구현 체크

- Controller에는 `@Valid`를 적용하고, trim·정규화·중복·상태·권한 검사는 서비스/도메인 계층에서 다시 수행한다.
- 생성 및 command API는 `Idempotency-Key`를 별도 header로 받는다.
- 세션 토큰, 진행자 토큰, participant ID를 request body DTO에 추가하지 않는다.
- 검증 오류에는 원문 문장이나 토큰을 다시 담지 않는다.
