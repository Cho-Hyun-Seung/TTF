# TTF API 명세

- 버전: `v1`
- 기준일: `2026-09-06`
- Base path: `/api/v1`
- 데이터 형식: JSON UTF-8, `snake_case`
- 실시간 전송: Server-Sent Events(SSE)
- 구현 기준: 현재 `backend` Controller, Request/Response DTO, 도메인 상태 전이와 통합 테스트

이 문서는 현재 백엔드가 제공하는 계약을 정의한다. 제품 정책은 `platformprd.md`를 따르며, 충돌할 경우 PRD 22장의 확정 답변, MVP 수용 기준, P0 요구사항 순으로 우선한다.

## 1. API 경계

- `/rooms`는 방 생성, 방 코드 조회, 참가, 참가자 내보내기와 방 취소를 담당한다.
- `/games/ttf`는 TTF 게임 스냅샷, 참가자 입력, 진행자 명령과 실시간 이벤트를 담당한다.
- `room_id`와 `game_id`는 서로 다른 불투명 식별자다.
- 방 생성 시 방과 TTF 게임 하나를 함께 생성한다.
- 참가 링크에는 방 코드만 포함하며 세션 토큰이나 진행자 권한을 포함하지 않는다.
- 게임 시작 후 신규 참가는 허용하지 않으며 관전자 참가 API도 제공하지 않는다.
- 계정, 게임 히스토리, 종료 게임 목록 API는 MVP 범위에 없다.

## 2. 공통 계약

### 2.1 식별자와 시간

- `room_id`, `game_id`, `participant_id`, `round_id`, `statement_id`는 의미를 해석할 수 없는 문자열로 취급한다.
- 클라이언트는 UUID 형식이나 접두사 길이에 의존하지 않는다.
- 방 코드는 대소문자를 구분하지 않는 영문·숫자 6자리다. 응답에는 생성된 대문자 코드를 반환한다.
- 모든 시각은 UTC ISO 8601 문자열이다.
- 게임 `version`은 상태가 변경될 때 증가하는 정수다.
- SSE의 `id`는 스트림 재연결용 순번이며 게임 `version`과 별개다.

### 2.2 성공 응답

JSON 응답이 있는 API는 `ApiResponse<T>`로 반환한다.

```json
{
  "success": true,
  "data": {},
  "response_time": "2026-09-06T12:34:56.789Z"
}
```

| 상황 | HTTP status | body |
|---|---:|---|
| 리소스 생성 | `201 Created` | `ApiResponse<Response>` |
| 조회 | `200 OK` | `ApiResponse<Response>` |
| 명령 또는 수정 성공 | `204 No Content` | 없음 |
| SSE 연결 | `200 OK` | `text/event-stream` |

- Service는 API별 Response DTO를 생성한다.
- Controller는 Response DTO를 `ApiResponse.success(...)`로 감싼다.
- `204 No Content`와 SSE에는 `ApiResponse`를 사용하지 않는다.
- 값이 `null`인 선택 필드는 JSON에서 생략된다.
- `response_time`은 JSON envelope를 만든 시각이다.

### 2.3 오류 응답

오류는 `ApiResponse<Void>` 형식으로 반환하며 `data`는 생략한다.

```json
{
  "success": false,
  "error_code": "NICKNAME_TAKEN",
  "message": "이미 사용 중인 닉네임이에요. 다른 이름을 입력해 주세요.",
  "response_time": "2026-09-06T12:34:56.789Z"
}
```

| HTTP | `error_code` | 의미 |
|---:|---|---|
| `400` | `VALIDATION_ERROR` | 필수값, 형식, 길이, enum 또는 `Idempotency-Key` 오류 |
| `401` | `SESSION_REQUIRED` | 필요한 세션이 없거나 알 수 없는 세션 |
| `403` | `HOST_PERMISSION_REQUIRED` | 대상 방의 진행자 권한이 없음 |
| `403` | `PARTICIPANT_PERMISSION_REQUIRED` | 대상 방의 참가자 권한이 없음 |
| `404` | `ROOM_NOT_FOUND` | 방을 찾을 수 없음 |
| `404` | `GAME_NOT_FOUND` | TTF 게임을 찾을 수 없음 |
| `404` | `ROUND_NOT_FOUND` | 현재 게임의 라운드를 찾을 수 없음 |
| `409` | `INVALID_STATE_TRANSITION` | 현재 상태에서 허용되지 않는 동작 |
| `409` | `NICKNAME_TAKEN` | 같은 방에 정규화 결과가 같은 닉네임이 존재함 |
| `409` | `ROOM_FULL` | 방 정원 도달 |
| `409` | `GAME_ALREADY_STARTED` | 게임 시작 후 참가 시도 |
| `409` | `NOT_ENOUGH_PARTICIPANTS` | 게임 시작에 필요한 참가자 수 미달 |
| `409` | `PARTICIPANTS_NOT_READY` | 문장 제출을 완료하지 않은 참가자가 존재함 |
| `409` | `SPEAKER_CANNOT_VOTE` | 발표자가 자기 라운드에 투표함 |
| `409` | `VOTING_NOT_OPEN` | 투표 전, 마감 후 또는 서버 마감 시각 이후 투표함 |
| `409` | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등성 키를 다른 body에 재사용함 |
| `410` | `ROOM_EXPIRED` | 만료 또는 정리 대상 방에 접근함 |
| `429` | `RATE_LIMITED` | 요청 또는 SSE 연결 제한 초과 |
| `500` | `INTERNAL_ERROR` | 공개할 수 없는 서버 오류 |

추가 HTTP 처리:

- 지원하지 않는 HTTP method는 `405`와 `VALIDATION_ERROR`를 반환한다.
- 신뢰할 수 없는 변경 요청 출처는 `403`과 `SESSION_REQUIRED`를 반환한다.
- `429`에는 재시도 가능 시각을 초 단위로 나타내는 `Retry-After` 헤더가 포함된다.
- 오류 메시지에는 원문 문장, 토큰, 내부 예외나 스택 트레이스를 포함하지 않는다.

### 2.4 세션 쿠키와 권한

방 생성과 참가 성공 시 각각 별도 세션 쿠키를 발급한다.

```http
Set-Cookie: ttf_host_session=<opaque>; Path=/api/v1; HttpOnly; Secure; SameSite=Lax
Set-Cookie: ttf_participant_session=<opaque>; Path=/api/v1; HttpOnly; Secure; SameSite=Lax
```

- 운영 기본값은 `Secure=true`다. 로컬 HTTP 환경에서는 설정으로 비활성화할 수 있다.
- 세션 토큰은 URL과 JSON body에 포함하지 않는다.
- 한 쿠키는 여러 방의 권한을 가질 수 있지만 권한 검사는 항상 대상 방까지 확인한다.
- 참가자 쿠키로 진행자 API를 호출하면 `HOST_PERMISSION_REQUIRED`다.
- 진행자 쿠키로 참가자 API를 호출하면 `PARTICIPANT_PERMISSION_REQUIRED`다.
- 프론트엔드는 쿠키가 필요한 요청에 `credentials: include`를 사용한다.

모든 `POST`, `PUT`, `DELETE` 요청은 신뢰 가능한 출처여야 한다.

- `Origin`이 설정된 경우 같은 origin 또는 서버 allowlist와 일치해야 한다.
- `Origin`이 없으면 `Sec-Fetch-Site: same-origin`이어야 한다.
- credential CORS 허용 method는 `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`다.
- 허용 요청 헤더는 `Content-Type`, `Accept`, `Idempotency-Key`, `Last-Event-ID`다.

### 2.5 멱등성

다음 API는 `Idempotency-Key` 헤더가 필수다.

- 방 생성과 참가
- 참가자 내보내기와 방 취소
- 모든 진행자 게임 명령

```http
Idempotency-Key: 1fd98a11-1ea2-4755-b316-c2177f8ca128
```

- 키는 공백이 아닌 최대 200자 문자열이다.
- 멱등성 범위는 `세션 + endpoint + key`다.
- 같은 범위와 같은 body의 재요청은 상태 변경을 반복하지 않고 저장된 Response DTO 또는 명령 결과를 재사용한다.
- 같은 키에 다른 body를 사용하면 `409 IDEMPOTENCY_KEY_REUSED`다.
- 성공 JSON envelope는 Controller가 요청마다 새로 생성하므로 `data`는 같지만 `response_time`은 달라질 수 있다.
- `204` 명령 재요청은 계속 `204`를 반환한다.
- 문장 저장과 투표는 `PUT` 리소스 의미로 멱등 동작한다. 별도 `Idempotency-Key`는 받지 않는다.
- 방이나 게임이 정리되면 관련 멱등성 기록도 제거한다.

### 2.6 캐시

- 방 코드 조회: `Cache-Control: no-store`
- 게임 스냅샷: `Cache-Control: private, no-store`, `Vary: Cookie`
- SSE: `Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no`

## 3. 상태와 공개 정책

### 3.1 방 상태

```text
OPEN -> IN_GAME -> CLOSED
  \----------------> CLOSED
OPEN 또는 IN_GAME -> EXPIRED
```

- `OPEN`: 참가와 시작 전 수정이 가능한 방
- `IN_GAME`: 게임이 시작되어 참가가 잠긴 방
- `CLOSED`: 취소 또는 게임 종료 후 정리 대기 중인 방
- `EXPIRED`: 활성 TTL을 초과해 정리 대기 중인 방

### 3.2 TTF 게임 상태

```text
LOBBY <-> SUBMISSION <-> READY
READY -> ROUND_INTRO -> VOTING -> VOTE_CLOSED -> RESULT
                    \                         /
                     -> 다음 ROUND_INTRO 또는 FINISHED
ROUND_INTRO | VOTING | VOTE_CLOSED | RESULT -> PAUSED -> 이전 상태
진행 중 상태 -> FINISHED
시작 전 상태 -> CANCELLED
```

- `LOBBY`: 참가자가 없음
- `SUBMISSION`: 참가자가 있으나 2명 미만이거나 전원이 준비되지 않음
- `READY`: 참가자가 2명 이상이고 전원이 문장 제출 완료
- `ROUND_INTRO`: 현재 발표자와 문장이 공개된 투표 전 단계
- `VOTING`: 서버 마감 시각 전까지 투표 가능
- `VOTE_CLOSED`: 투표 마감, 결과 공개 전
- `RESULT`: 현재 라운드 정답과 점수 공개 완료
- `PAUSED`: 이전 상태와 투표 잔여 시간을 보존한 일시 정지
- `FINISHED`: 최종 순위 확정
- `CANCELLED`: 시작 전 취소

### 3.3 비공개 정보

- `ROUND_INTRO`, `VOTING`, `VOTE_CLOSED`에는 문장 진위, 정답 ID, 개별 투표자와 득표 결과를 공개하지 않는다.
- 진행자도 결과 공개 전에는 정답 정보를 받지 않는다.
- 참가자는 시작 전 자기 `my_statement_sets[].statements`에서만 `is_fake`를 볼 수 있다.
- 결과는 `RESULT` 이후 현재 라운드의 `result`로 공개한다.
- `anonymous_voting=true`이면 결과 공개 후에도 `voters`를 생략한다.
- SSE payload에는 원문 문장, 진위, 투표 대상 ID나 세션 정보를 넣지 않는다.

### 3.4 점수와 순위

- 가짜 문장을 고른 참가자에게 라운드당 1점을 준다.
- 발표자는 가짜 문장을 고르지 않은 참가자 1명당 1점을 받는다.
- 모든 참가자를 속여도 별도의 무득표 보너스는 없다.
- 점수는 결과 공개 명령에서 한 번만 반영한다.
- 동점은 공동 순위이며 다음 순위는 경쟁 순위 방식이다. 예: `1, 1, 3`.

## 4. Endpoint 요약

### 4.1 방 API

| Method | Path | 권한 | 필수 헤더 | 성공 |
|---|---|---|---|---:|
| `POST` | `/rooms` | 공개 | `Idempotency-Key` | `201` |
| `GET` | `/rooms/by-code/{code}` | 공개 | - | `200` |
| `POST` | `/rooms/{room_id}/participants` | 공개 | `Idempotency-Key` | `201` |
| `DELETE` | `/rooms/{room_id}/participants/{participant_id}` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/rooms/{room_id}/commands/cancel` | 진행자 | `Idempotency-Key` | `204` |

### 4.2 TTF API

| Method | Path | 권한 | 필수 헤더 | 성공 |
|---|---|---|---|---:|
| `GET` | `/games/ttf/topics` | 공개 | - | `200` |
| `GET` | `/games/ttf/{game_id}/snapshot?audience=...` | audience별 | - | `200` |
| `GET` | `/games/ttf/{game_id}/events?audience=...` | audience별 | `Accept: text/event-stream` 권장 | `200` |
| `PUT` | `/games/ttf/{game_id}/participants/me/statements` | 참가자 | - | `204` |
| `PUT` | `/games/ttf/{game_id}/rounds/{round_id}/vote` | 참가자 | - | `204` |
| `POST` | `/games/ttf/{game_id}/commands/start` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/start-voting` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/extend-voting` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/close-voting` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/reveal-result` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/skip` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/games/ttf/{game_id}/commands/next-round` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/games/ttf/{game_id}/commands/pause` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/games/ttf/{game_id}/commands/resume` | 진행자 | `Idempotency-Key` | `204` |
| `POST` | `/games/ttf/{game_id}/commands/finish` | 진행자 | `Idempotency-Key` | `204` |

표의 모든 path 앞에는 `/api/v1`이 붙는다. 모든 변경 요청에는 2.4절의 출처 검증도 적용된다.

## 5. 방 API

### 5.1 방 생성

`POST /api/v1/rooms`

요청:

```json
{
  "name": "마케팅팀 금요 워크숍",
  "settings": {
    "max_participants": 30
  },
  "game": {
    "type": "TTF",
    "settings": {
      "statement_max_length": 100,
      "voting_duration_seconds": 60,
      "speaker_order": "RANDOM",
      "anonymous_voting": true,
      "round_count": 3,
      "topic_ids": ["TRAVEL", "FOOD", "TALENT"]
    }
  }
}
```

검증:

- `name`: 공백이 아닌 최대 40자
- `max_participants`: `2..100`
- `game.type`: `TTF`
- `statement_max_length`: `20..200`
- `voting_duration_seconds`: `15..180`
- `speaker_order`: `RANDOM` 또는 `JOIN_ORDER`
- `anonymous_voting`: boolean
- `round_count`: `1..8`
- `topic_ids`: 서버 주제 카탈로그의 ID로 구성된 중복 없는 배열이며 개수가 `round_count`와 같아야 함
- 문장 최소 길이는 MVP에서 5자로 고정

응답: `201 Created`, 진행자 쿠키 발급

```json
{
  "success": true,
  "data": {
    "room": {
      "id": "room_01J7J...",
      "code": "A7K2Q9",
      "join_url": "https://ttf.example/join/A7K2Q9"
    },
    "game": {
      "id": "game_01J7J...",
      "type": "TTF"
    }
  },
  "response_time": "2026-09-06T12:34:56.789Z"
}
```

### 5.2 방 코드 조회

`GET /api/v1/rooms/by-code/{code}`

- `{code}`는 영문·숫자 6자리여야 한다.
- 형식이 잘못되거나 존재하지 않으면 정보 탐색을 막기 위해 `404 ROOM_NOT_FOUND`다.
- 시작 또는 종료된 방도 정리 전까지 조회될 수 있으며 이때 `joinable=false`다.

응답: `200 OK`

```json
{
  "success": true,
  "data": {
    "id": "room_01J7J...",
    "code": "A7K2Q9",
    "name": "마케팅팀 금요 워크숍",
    "status": "OPEN",
    "joinable": true,
    "participant_count": 3,
    "settings": {
      "max_participants": 30
    },
    "active_game": {
      "id": "game_01J7J...",
      "type": "TTF"
    }
  },
  "response_time": "2026-09-06T12:34:56.789Z"
}
```

### 5.3 방 참가

`POST /api/v1/rooms/{room_id}/participants`

요청:

```json
{
  "nickname": "민준"
}
```

- `nickname`: 공백이 아닌 최대 20자
- 비교 시 Unicode 정규화, 대소문자 정규화, 앞뒤·연속 공백 정규화를 적용한다.
- 같은 방에서 정규화된 닉네임은 중복될 수 없다.
- 정원 확인과 닉네임 등록은 원자적으로 처리한다.
- 방이 `OPEN`이고 게임이 `LOBBY`, `SUBMISSION`, `READY`일 때만 참가할 수 있다.
- 기존 참가 세션으로 동일한 닉네임에 재접속하면 같은 참가자 권한을 복구한다.

응답: `201 Created`, 참가자 쿠키 발급

```json
{
  "success": true,
  "data": {
    "room_id": "room_01J7J...",
    "participant_id": "participant_02",
    "game": {
      "id": "game_01J7J...",
      "type": "TTF"
    }
  },
  "response_time": "2026-09-06T12:34:56.789Z"
}
```

### 5.4 참가자 내보내기

`DELETE /api/v1/rooms/{room_id}/participants/{participant_id}`

- 진행자 쿠키와 `Idempotency-Key`가 필요하다.
- 게임 시작 전만 허용한다.
- 참가자, TTF player, 제출 문장과 참가 세션 권한을 함께 제거한다.
- 준비 상태와 게임 상태를 다시 계산한다.
- 이미 제거된 참가자에 대한 동일 요청은 `204`로 처리한다.

응답: `204 No Content`

### 5.5 방 취소

`POST /api/v1/rooms/{room_id}/commands/cancel`

- 진행자 쿠키와 `Idempotency-Key`가 필요하다.
- `OPEN` 방의 시작 전 게임만 취소할 수 있다.
- 방은 `CLOSED`, 게임은 `CANCELLED`가 되고 종료 정리를 예약한다.

응답: `204 No Content`

### 5.6 TTF 주제 카탈로그

`GET /api/v1/games/ttf/topics`

- 인증 없이 조회할 수 있다.
- Java 서버에 정의된 주제 ID, 사용자용 제목, 참고 예시를 순서대로 반환한다.
- 클라이언트는 별도의 주제 목록을 하드코딩하지 않고 이 응답을 사용한다.

응답: `200 ApiResponse<TtfTopicResponse[]>`

```json
{
  "success": true,
  "data": [
    {
      "id": "TRAVEL",
      "title": "여행",
      "example": "나는 혼자 해외여행을 떠난 적이 있다."
    }
  ],
  "response_time": "2026-09-08T00:00:00Z"
}
```

## 6. TTF 스냅샷

### 6.1 조회

`GET /api/v1/games/ttf/{game_id}/snapshot?audience={audience}`

| `audience` | 인증 | 용도 |
|---|---|---|
| `participant` | 해당 방 참가자 쿠키 | 개인 플레이 화면 |
| `host` | 해당 방 진행자 쿠키 | 진행 화면과 참가자 관리 |
| `display` | 불필요 | 공개 화면 |

- `audience`는 대소문자를 구분하지 않는다.
- `display`는 진행자 쿠키가 함께 전송돼도 공개 범위만 반환한다.
- 조회 시 투표 마감 시각이 지났다면 서버가 먼저 `VOTE_CLOSED`를 반영한다.
- 전원 투표 후 자동 공개 시각이 지났다면 조회 시에도 `RESULT` 전환을 보정한다. `current_round.result_reveals_at`은 자동 공개를 기다리는 `VOTE_CLOSED`에서만 제공하는 UTC 예정 시각이며 정답·득표 데이터는 포함하지 않는다.
- 새로고침, 최초 접속, SSE 재연결과 이벤트 누락 복구는 이 API를 사용한다.

응답: `200 ApiResponse<TtfGameSnapshotResponse>`

### 6.2 스냅샷 예시

아래는 `participant`가 `VOTING` 상태를 조회한 예시다.

```json
{
  "success": true,
  "data": {
    "version": 17,
    "server_time": "2026-09-06T12:34:56.789Z",
    "room": {
      "id": "room_01J7J...",
      "code": "A7K2Q9",
      "name": "마케팅팀 금요 워크숍",
      "status": "IN_GAME",
      "joinable": false,
      "participant_count": 3,
      "settings": {
        "max_participants": 30
      },
      "active_game": {
        "id": "game_01J7J...",
        "type": "TTF"
      }
    },
    "game": {
      "id": "game_01J7J...",
      "type": "TTF",
      "status": "VOTING",
      "ready_count": 3,
      "round_count": 3,
      "current_round_number": 1,
      "settings": {
        "statement_min_length": 5,
        "statement_max_length": 100,
        "voting_duration_seconds": 60,
        "speaker_order": "RANDOM",
        "anonymous_voting": true,
        "round_count": 1,
        "topics": [
          {
            "id": "TRAVEL",
            "title": "여행",
            "example": "나는 혼자 해외여행을 떠난 적이 있다."
          }
        ]
      }
    },
    "viewer": {
      "role": "PARTICIPANT",
      "participant_id": "participant_02",
      "nickname": "민준",
      "is_ready": true
    },
    "current_round": {
      "id": "round_01",
      "number": 1,
      "total": 3,
      "topic": {
        "id": "TRAVEL",
        "title": "여행",
        "example": "나는 혼자 해외여행을 떠난 적이 있다."
      },
      "speaker": {
        "id": "participant_01",
        "nickname": "지수"
      },
      "statements": [
        {
          "id": "statement_01",
          "content": "나는 사막에서 밤을 보낸 적이 있다.",
          "display_order": 1
        },
        {
          "id": "statement_02",
          "content": "나는 커피를 한 번도 마신 적이 없다.",
          "display_order": 2
        },
        {
          "id": "statement_03",
          "content": "나는 세 개의 악기를 연주할 수 있다.",
          "display_order": 3
        }
      ],
      "voting_started_at": "2026-09-06T12:34:00Z",
      "voting_ends_at": "2026-09-06T12:35:00Z",
      "vote_progress": {
        "completed": 1,
        "eligible": 2
      },
      "my_vote_statement_id": "statement_02"
    }
  },
  "response_time": "2026-09-06T12:34:56.790Z"
}
```

### 6.3 audience별 필드

| 필드 | participant | host | display |
|---|:---:|:---:|:---:|
| `room`, `game`, `current_round` | O | O | O |
| `viewer.role` | O | O | O |
| `viewer.participant_id`, `nickname`, `is_ready` | O | - | - |
| 시작 전 `my_statement_sets` | O | - | - |
| `current_round.my_vote_statement_id` | O | - | - |
| `participants` | - | O | - |
| 공개된 `current_round.result` | O | O | O |
| `leaderboard` | O | O | O |

- `my_statement_sets`는 `LOBBY`, `SUBMISSION`, `READY`에서만 포함하며 선택된 모든 주제와 본인 문장을 포함한다.
- `participants`에는 `id`, `nickname`, `connection_status`, `is_ready`, `score`, `is_current_speaker`가 포함된다.
- `leaderboard`는 `FINISHED`에서만 포함한다.
- `leaderboard[].is_me`는 participant 본인만 `true`이고 host/display에서는 모두 `false`다.
- `game.paused_from_status`는 `PAUSED`에서만 포함한다.

### 6.4 결과 구조

`RESULT` 이후 `current_round.result`:

```json
{
  "fake_statement_id": "statement_02",
  "statements": [
    {
      "id": "statement_01",
      "content": "나는 사막에서 밤을 보낸 적이 있다.",
      "display_order": 1,
      "is_fake": false,
      "vote_count": 0,
      "vote_rate": 0.0
    },
    {
      "id": "statement_02",
      "content": "나는 커피를 한 번도 마신 적이 없다.",
      "display_order": 2,
      "is_fake": true,
      "vote_count": 2,
      "vote_rate": 100.0
    }
  ],
  "correct_voter_count": 2,
  "fooled_participant_count": 0,
  "score_changes": [
    {
      "participant_id": "participant_02",
      "nickname": "민준",
      "delta": 1,
      "total": 2
    }
  ]
}
```

`anonymous_voting=false`이면 각 statement 결과에 다음 필드가 추가된다.

```json
{
  "voters": [
    {
      "id": "participant_02",
      "nickname": "민준"
    }
  ]
}
```

## 7. 참가자 TTF API

### 7.1 문장 저장 또는 전체 교체

`PUT /api/v1/games/ttf/{game_id}/participants/me/statements`

요청:

```json
{
  "statement_sets": [
    {
      "topic_id": "TRAVEL",
      "statements": [
        {
          "content": "나는 사막에서 밤을 보낸 적이 있다.",
          "is_fake": false
        },
        {
          "content": "나는 커피를 한 번도 마신 적이 없다.",
          "is_fake": true
        },
        {
          "content": "나는 세 개의 악기를 연주할 수 있다.",
          "is_fake": false
        }
      ]
    }
  ]
}
```

- 해당 방의 참가자 쿠키가 필요하다.
- `statement_sets`는 방 설정의 모든 주제를 정확히 한 번씩 포함해야 한다.
- 각 주제의 문장은 정확히 3개이고 `is_fake=true`는 정확히 1개여야 한다.
- 공백 정리 후 각 문장은 설정된 `statement_min_length..statement_max_length`를 만족해야 한다.
- Unicode와 공백 정규화 후 주제 안팎에서 같은 내용은 중복할 수 없다.
- `LOBBY`, `SUBMISSION`, `READY`에서만 저장할 수 있다.
- 저장 성공 시 참가자를 준비 완료로 표시하고 전체 준비 상태를 다시 계산한다.
- 배열 순서와 실제 공개 순서는 무관하며 공개 순서는 서버가 결정한다.
- 자동 금칙어 필터를 적용하지 않는다.

응답: `204 No Content`

### 7.2 투표 등록 또는 변경

`PUT /api/v1/games/ttf/{game_id}/rounds/{round_id}/vote`

요청:

```json
{
  "statement_id": "statement_02"
}
```

- 해당 방의 참가자 쿠키가 필요하다.
- voter는 세션에서 결정하며 body로 받지 않는다.
- 현재 라운드가 `VOTING`이고 서버 시각이 `voting_ends_at` 전이어야 한다.
- statement는 현재 라운드에 속해야 한다.
- 발표자는 투표할 수 없다.
- 참가자 한 명은 라운드당 한 표만 가지며 다시 요청하면 선택 statement를 변경한다.
- 같은 statement를 다시 선택해도 표 수와 version을 증가시키지 않는다.
- 발표자를 제외한 전원의 표가 저장되면 마지막 표 저장과 마감을 원자적으로 처리한다. 마지막 투표는 `204`로 승인하고, 이후 재전송·변경은 `409 VOTING_NOT_OPEN`으로 거절한다.
- 전원 투표 마감 시 `voting.closed`와 `game.status_changed`를 발행하고 2초 후 서버가 자동으로 결과를 공개한다. `current_round.result_reveals_at` 이전에는 진행자 명령으로도 조기 공개할 수 없다.
- 자동 공개는 `round.result_revealed`, `score.updated`, `game.status_changed`로 알리고, 클라이언트는 전체 스냅샷을 조회한다. 제한 시간 종료 또는 진행자의 조기 마감은 기존 수동 결과 공개를 유지한다.
- 마감과 경합하면 서버에서 마감을 먼저 반영하고 `409 VOTING_NOT_OPEN`을 반환할 수 있다.

응답: `204 No Content`

## 8. 진행자 명령 API

모든 진행자 명령은 해당 방의 진행자 쿠키와 `Idempotency-Key`를 요구하며 성공 시 `204 No Content`를 반환한다.

| 명령 | 허용 상태 | 처리 결과 |
|---|---|---|
| `POST /games/ttf/{game_id}/commands/start` | `READY` | 발표 순서를 확정하고 첫 라운드를 `ROUND_INTRO`, 방을 `IN_GAME`으로 전환 |
| `POST /games/ttf/{game_id}/rounds/{round_id}/commands/start-voting` | `ROUND_INTRO` | 투표 시작·마감 시각을 기록하고 자동 마감 예약 |
| `POST /games/ttf/{game_id}/rounds/{round_id}/commands/extend-voting` | 마감 전 `VOTING` | 기존 마감 시각에 요청 초를 더하고 자동 마감 재예약 |
| `POST /games/ttf/{game_id}/rounds/{round_id}/commands/close-voting` | `VOTING` | 즉시 `VOTE_CLOSED`로 전환하고 자동 마감 취소 |
| `POST /games/ttf/{game_id}/rounds/{round_id}/commands/reveal-result` | `VOTE_CLOSED` | 결과 집계, 정답자 1점 반영 후 `RESULT`로 전환 |
| `POST /games/ttf/{game_id}/rounds/{round_id}/commands/skip` | `ROUND_INTRO` | 점수 없이 현재 라운드를 건너뛰고 다음 라운드 또는 종료 |
| `POST /games/ttf/{game_id}/commands/next-round` | `RESULT` | 다음 라운드 또는 최종 순위 확정 후 종료 |
| `POST /games/ttf/{game_id}/commands/pause` | `ROUND_INTRO`, `VOTING`, `VOTE_CLOSED`, `RESULT` | 이전 상태와 투표 잔여 시간 보존 후 `PAUSED` |
| `POST /games/ttf/{game_id}/commands/resume` | `PAUSED` | 이전 상태로 복원하고 필요 시 자동 마감 재예약 |
| `POST /games/ttf/{game_id}/commands/finish` | 진행 중 또는 `PAUSED` | 공개 완료 점수로 최종 순위를 확정하고 `FINISHED`, 방을 `CLOSED`로 전환 |

투표 연장 요청 body:

```json
{
  "seconds": 15
}
```

- `seconds`는 `5..60` 정수다.
- 이미 마감 시각이 지났다면 먼저 투표를 닫으며 연장은 실패한다.
- 일시 정지 요청이 투표 마감과 경합하면 마감을 먼저 반영할 수 있다.
- 자동 마감과 수동 마감은 원자적으로 한 번만 상태를 변경한다.
- 전원 투표 후 자동 공개는 라운드·게임 상태·공개 예정 시각을 다시 확인하고 결과와 점수를 한 번만 반영한다. 일시 정지 시 공개 잔여 시간을 보존하고 재개 시 재예약하며, 종료된 게임이나 이전 라운드의 예약은 결과를 변경하지 않는다.
- 결과 공개 재시도로 점수가 중복 반영되지 않는다.

## 9. SSE 실시간 이벤트

### 9.1 연결

`GET /api/v1/games/ttf/{game_id}/events?audience={audience}`

인증과 공개 대상은 스냅샷의 `audience` 규칙과 같다.

요청 예시:

```http
GET /api/v1/games/ttf/game_01J7J.../events?audience=participant
Accept: text/event-stream
Last-Event-ID: 17
Cookie: ttf_participant_session=<opaque>
```

응답 헤더:

```http
Content-Type: text/event-stream
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no
```

- 연결 직후 `: connected` comment를 보낸다.
- 20초마다 `: heartbeat` comment를 보낸다.
- 각 전송에 `retry: 3000`을 지정한다.
- emitter timeout은 30분이며 클라이언트는 종료 시 재연결해야 한다.
- 참가자 연결이 모두 끊긴 뒤 5초 동안 재연결되지 않으면 `OFFLINE`으로 반영한다.

### 9.2 이벤트 형식

```text
id: 18
event: vote.progress_changed
retry: 3000
data: {"event_id":"evt_01J7K...","room_id":"room_01J7J...","game_id":"game_01J7J...","version":18,"occurred_at":"2026-09-06T12:35:01Z"}

```

- SSE `id`는 게임 스트림 내 단조 증가 순번이다.
- payload의 `event_id`는 개별 이벤트 식별용 불투명 문자열이다.
- payload는 변경 알림만 전달한다. 최종 화면 상태는 스냅샷으로 확인한다.

### 9.3 재연결

- 서버는 게임별 최근 이벤트 최대 256개를 메모리에 유지한다.
- 유효한 `Last-Event-ID`가 보관 범위 안에 있으면 이후 이벤트를 순서대로 재전송한다.
- ID가 숫자가 아니거나 음수, 미래 값 또는 보관 범위보다 오래된 값이면 `game.sync_required`를 보낸다.
- 연결별 대기 이벤트가 512개를 넘으면 연결을 종료한다. 재연결 후 스냅샷 또는 이벤트 복구 절차를 따른다.
- 프론트엔드는 이벤트 중복, 역순, version 공백을 발견하면 즉시 스냅샷을 다시 조회한다.

### 9.4 연결 제한

| 범위 | 제한 |
|---|---:|
| 동일 subject와 게임 | 최대 2개 |
| 동일 subject 전체 게임 | 최대 8개 |
| 동일 client address | 최대 256개 |

제한 초과 시 `429 RATE_LIMITED`, `Retry-After: 3`을 반환한다.

### 9.5 이벤트 목록

| 이벤트 | 발생 시점 |
|---|---|
| `participant.joined` | 참가자 등록 후 |
| `participant.left` | 참가자 제거 또는 연결 상태 변경 후 |
| `participant.ready_changed` | 문장 저장으로 준비 상태가 변경된 후 |
| `game.status_changed` | 게임 상태가 변경된 후 |
| `round.started` | 새 라운드가 `ROUND_INTRO`로 열린 후 |
| `voting.started` | 투표 시작, 연장 또는 투표 상태 재개 후 |
| `vote.progress_changed` | 참가자의 최초 유효 투표로 완료 수가 변경된 후 |
| `voting.closed` | 자동 또는 수동 투표 마감 후 |
| `round.result_revealed` | 라운드 결과 공개 후 |
| `score.updated` | 점수 반영 후 |
| `game.finished` | 최종 순위 확정 후 |
| `game.sync_required` | 이벤트 연속성을 보장할 수 없거나 방이 만료된 경우 |

## 10. 요청 제한

현재 구현은 고정 시간 창 방식으로 다음 제한을 적용한다.

| 동작 | 식별 범위 | 제한 |
|---|---|---:|
| 방 생성 | client address | 분당 10회 |
| 방 코드 조회 | client address | 분당 60회 |
| 방 참가 | client address + 방 | 분당 20회 |
| 참가자 내보내기 | 진행자 세션 | 초당 5회 |
| 방 취소 | 진행자 세션 | 초당 5회 |
| 문장 저장 | 참가자 세션 | 분당 10회 |
| 투표 등록·변경 | 참가자 세션 | 초당 5회 |
| TTF 진행자 명령 | 진행자 세션 | 초당 5회, 설정 가능 |

SSE 연결 제한은 9.4절을 따른다.

## 11. 수명 주기와 삭제

기본 설정:

| 설정 | 기본값 |
|---|---:|
| 활성 방 TTL | 6시간 |
| 종료 상태 grace period | 5분 |
| 정리 sweep 주기 | 30초 |

- 활성 TTL을 넘은 `OPEN` 또는 `IN_GAME` 방은 `EXPIRED`로 전환하고 `game.sync_required`를 발행한다.
- `FINISHED`, `CANCELLED`, `EXPIRED` 방은 grace period 동안 마지막 상태를 전달할 수 있다.
- 삭제 시 방, 게임, 참가자, 문장, 라운드, 투표와 점수 데이터를 제거한다.
- 방에 연결된 진행자·참가자 권한, 투표 스케줄, SSE 스트림과 멱등성 기록도 함께 제거한다.
- 종료 게임 조회, 영구 기록과 히스토리 API는 제공하지 않는다.

## 12. 클라이언트 구현 체크리스트

- 모든 필드명을 `snake_case`로 처리한다.
- 생성·조회 JSON 응답에서 `data`를 사용하고 `204`에서는 body를 파싱하지 않는다.
- 방 생성·참가 응답의 `game.id`를 TTF API path에 사용한다.
- `room_id`와 `game_id`를 혼용하지 않는다.
- 쿠키가 필요한 요청은 `credentials: include`로 전송한다.
- 모든 변경 요청에 신뢰 가능한 `Origin` 또는 `Sec-Fetch-Site`가 전달되도록 한다.
- 멱등성 대상 요청마다 안정적인 `Idempotency-Key`를 생성하고 재시도에는 같은 키를 사용한다.
- 멱등 재요청의 `response_time` 일치를 기대하지 않고 `data`와 HTTP status를 기준으로 처리한다.
- 투표 타이머는 `server_time`과 `voting_ends_at`으로 표시하되 마감 판정은 서버 응답을 따른다.
- 공개 전 `is_fake`, 결과와 개별 투표 정보를 클라이언트 상태에 미리 저장하지 않는다.
- SSE 이벤트를 최종 상태로 사용하지 않고 snapshot version을 기준으로 동기화한다.
- `game.sync_required`, 이벤트 누락, 역순 또는 연결 종료 시 스냅샷을 다시 조회한다.
