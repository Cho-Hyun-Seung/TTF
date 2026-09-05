# TTF 게임 플랫폼 API 명세

- 버전: `v1`
- 기준 문서: `platformprd.md` v0.1
- 대상: React 프론트엔드와 게임 플랫폼 백엔드
- Base path: `/api/v1`
- 데이터 형식: JSON, UTF-8, 필드명 `snake_case`
- 실시간 전송: Server-Sent Events(SSE)

이 문서는 현재 `frontend/` 구현이 기대하는 계약이다. `platformprd.md` 22장의 확정 결정을 반영해 점수는 정답자 1점만 계산하고, 게임 시작 후 신규 입장을 막으며, 방 데이터는 활성 게임과 재접속에 필요한 동안만 유지한다.

### 리소스 경계

- `/rooms`는 게임 종류와 무관한 방 코드, 방 이름, 정원, 참가자 세션과 입장 가능 여부를 담당한다.
- `/games/{game_type}`은 게임별 설정, 상태, 입력, 라운드, 투표, 점수와 진행 명령을 담당한다. TTF의 `game_type` path 값은 `ttf`, JSON 값은 `TTF`다.
- `room_id`와 `game_id`는 서로 다른 식별자다. 클라이언트는 한 값을 다른 값으로 대신 사용하지 않는다.
- MVP에서는 방 하나에 활성 게임 하나만 둔다. 방 생성 요청의 판별 가능한 `game` 객체로 방과 게임을 한 트랜잭션에서 생성하며, 지원 게임이 늘어나면 새 `game.type`과 `/games/{game_type}` 계약을 추가한다.
- 참가 링크와 입장 API는 방을 가리키고, 입장 이후 플레이·진행·공용 화면은 응답으로 받은 `game.id`를 사용한다.
- 기존 초안의 `/rooms/{room_id}/rounds/...`, `/rooms/{room_id}/snapshot`, `/rooms/{room_id}/events`는 v1 계약에서 제거한다. 백엔드는 두 체계를 동시에 제공하지 않고 프론트엔드와 함께 전환한다.

## 1. 공통 규칙

### 1.1 식별자와 시간

- `room_id`, `game_id`, `participant_id`, `round_id`, `statement_id`는 외부에서 추측하기 어려운 불투명 문자열이다. UUIDv4/UUIDv7 사용을 권장하지만 클라이언트는 UUID 형식에 의존하지 않는다.
- 방 코드는 대문자 영문과 숫자로 구성된 6자리 문자열이다. 예: `A7K2Q9`.
- 모든 시각은 UTC ISO 8601 문자열로 반환한다. 예: `2026-09-05T10:30:00.000Z`.
- 비율은 `0`부터 `100` 사이 숫자이며 소수점 한 자리까지 허용한다.
- 서버 스냅샷의 `version`은 게임마다 단조 증가하는 정수다. 게임 상태에 영향을 주는 쓰기가 커밋될 때 증가한다.
- 참가·내보내기처럼 방 API에서 발생했지만 활성 게임 snapshot을 바꾸는 쓰기도 해당 게임 `version`을 증가시키고 게임 SSE에 알린다.

### 1.2 세션과 쿠키

회원가입이나 사용자 계정은 없다. 방 생성과 참가 성공 시 백엔드가 추측 불가능한 세션 토큰을 쿠키로 발급한다.

```http
Set-Cookie: ttf_host_session=<opaque>; Path=/api/v1; HttpOnly; Secure; SameSite=Lax
Set-Cookie: ttf_participant_session=<opaque>; Path=/api/v1; HttpOnly; Secure; SameSite=Lax
```

- 토큰 원문을 데이터베이스, 로그, URL 또는 API 응답 body에 기록하지 않는다. 서버에는 검증 가능한 해시만 저장한다.
- 로컬 HTTP 개발 환경에서는 `Secure`를 생략할 수 있지만 운영 환경에서는 필수다.
- 프론트엔드는 모든 요청에 `credentials: include`를 사용한다.
- 쿠키 하나가 여러 방의 권한을 가질 수 있도록 서버 세션에 방별 권한 목록을 둔다. `/rooms`에서 받은 자격 증명이 `/games`에도 전송되므로 모든 권한 검사는 대상 `room_id` 또는 게임이 속한 방까지 확인한다.
- 진행자와 참가자 쿠키를 분리해 진행자가 새 탭으로 공용 화면을 열어도 권한이 섞이지 않게 한다.
- `audience=display` 응답은 진행자 쿠키가 있어도 항상 공개용 필드만 반환한다.
- 상태 변경 요청은 `Origin`/`Sec-Fetch-Site`를 검증해 CSRF를 방어한다. API와 프론트엔드 origin이 다르면 명시적인 credential 포함 CORS allowlist를 사용하고 와일드카드 origin을 허용하지 않는다.

### 1.3 멱등성

생성 및 명령 API는 `Idempotency-Key` 헤더를 받는다.

```http
Idempotency-Key: 1fd98a11-1ea2-4755-b316-c2177f8ca128
```

- 같은 세션, 같은 endpoint, 같은 key와 같은 body의 재요청은 최초 응답의 status와 body를 반환한다.
- 같은 key에 다른 body가 오면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.
- 키는 최소한 활성 방이 유지되는 동안 기억한다.
- `PUT` 투표와 문장 저장은 리소스 자체도 멱등적이어야 한다.

### 1.4 성공 응답

- 생성: `201 Created`
- 조회: `200 OK`
- body가 필요 없는 명령/수정: `204 No Content`
- `204` 응답에는 JSON body를 넣지 않는다.

### 1.5 오류 응답

모든 오류는 다음 envelope를 사용한다.

```json
{
  "error": {
    "code": "NICKNAME_TAKEN",
    "message": "이미 사용 중인 닉네임이에요. 다른 이름을 입력해 주세요.",
    "field_errors": {
      "nickname": "같은 방에서 닉네임은 중복될 수 없습니다."
    },
    "request_id": "req_01J7JQ4M8ZA8YH3YJ4KP6FC8VA"
  }
}
```

- `message`는 사용자에게 보여도 되는 간결한 한국어 문장이다.
- `field_errors`는 선택 필드이며 key는 요청 body의 필드 경로다.
- 내부 예외, SQL, 스택 트레이스, 원문 문장, 세션 토큰을 포함하지 않는다.

| HTTP | 오류 코드 | 의미 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 형식, 길이 또는 필수 필드 오류 |
| 401 | `SESSION_REQUIRED` | 필요한 세션 쿠키가 없음 |
| 403 | `HOST_PERMISSION_REQUIRED` | 진행자 전용 API 호출 |
| 403 | `PARTICIPANT_PERMISSION_REQUIRED` | 해당 방 참가자가 아님 |
| 404 | `ROOM_NOT_FOUND` | 존재하지 않는 방 또는 임의 ID 탐색 방지 |
| 404 | `GAME_NOT_FOUND` | 존재하지 않는 게임 또는 방과 연결되지 않은 게임 |
| 404 | `ROUND_NOT_FOUND` | 방에 속하지 않는 라운드 |
| 409 | `INVALID_STATE_TRANSITION` | 현재 상태에서 실행할 수 없는 명령 |
| 409 | `NICKNAME_TAKEN` | 같은 방의 정규화된 닉네임 중복 |
| 409 | `ROOM_FULL` | 최대 인원 도달 |
| 409 | `GAME_ALREADY_STARTED` | 게임 시작 후 신규 입장 시도 |
| 409 | `NOT_ENOUGH_PARTICIPANTS` | 준비된 참가자가 2명 미만 |
| 409 | `PARTICIPANTS_NOT_READY` | 준비되지 않은 참가자가 존재함 |
| 409 | `SPEAKER_CANNOT_VOTE` | 발표자의 자기 라운드 투표 |
| 409 | `VOTING_NOT_OPEN` | 투표 전 또는 마감 후 요청 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 key를 다른 요청에 재사용 |
| 410 | `ROOM_EXPIRED` | 취소·만료 후 정리된 방 |
| 429 | `RATE_LIMITED` | 요청 횟수 제한 초과 |
| 500 | `INTERNAL_ERROR` | 공개할 수 없는 서버 오류 |

`429`에는 가능한 경우 `Retry-After` 헤더를 포함한다.

## 2. 상태와 공개 정책

방 상태와 TTF 게임 상태는 분리한다.

- 방 상태: `OPEN -> IN_GAME -> CLOSED`, 수명 만료 시 `EXPIRED`.
- `OPEN`에서만 입장할 수 있다. TTF 게임 시작 커밋과 함께 방을 `IN_GAME`으로 잠근다.
- TTF 게임이 완료되거나 취소되면 방도 `CLOSED`가 된다. `EXPIRED`는 방 수명 주기 상태이며 게임 진행 상태로 사용하지 않는다.

### 2.1 정상 상태 흐름

```text
LOBBY
  -> SUBMISSION
  -> READY
  -> ROUND_INTRO
  -> VOTING
  -> VOTE_CLOSED
  -> RESULT
  -> ROUND_INTRO (다음 발표자)
  -> FINISHED
```

- `LOBBY`: 방 생성 직후, 참가자가 아직 없음.
- `SUBMISSION`: 참가자 입장 또는 문장 작성 중. 준비 상태가 바뀔 때 서버가 재평가한다.
- `READY`: 2명 이상이고 현재 참가자가 모두 문장 제출을 완료함. 새 참가자가 들어오면 `SUBMISSION`으로 돌아갈 수 있다.
- 게임 시작은 `READY`에서만 가능하며 준비된 참가자를 기준으로 라운드 순서를 고정한다.
- `PAUSED`는 `ROUND_INTRO`, `VOTING`, `VOTE_CLOSED`, `RESULT`에서 진입할 수 있다. `paused_from_status`와 투표 잔여 시간을 보존한다.
- `CANCELLED`: 시작 전 방 취소.
- `FINISHED`: 전 라운드 완료 또는 진행자의 조기 종료.

### 2.2 정답과 투표 정보

- `ROUND_INTRO`, `VOTING`, `VOTE_CLOSED`의 snapshot/API/SSE에는 `is_fake`, `fake_statement_id`, 문장별 득표 수, 투표자 목록을 절대 포함하지 않는다.
- `RESULT` 이후에만 현재 라운드의 `result`를 포함한다.
- 진행 중 `vote_progress`에는 완료 인원과 투표 가능 인원만 넣는다.
- `anonymous_voting=true`이면 결과 공개 후에도 `voters` 필드를 생략한다.
- 진행자 역시 정답 공개 전에는 정답 정보를 받지 않는다.

### 2.3 점수와 순위

- 가짜 문장을 맞힌 투표자에게 라운드당 1점을 준다.
- 발표자 기만 점수와 무득표 보너스는 없다.
- 점수는 결과 공개 시 한 번만 반영한다. 동일 명령 재시도로 중복 가산하지 않는다.
- 동점은 공동 순위다. 다음 순위는 경쟁 순위 방식이다. 예: `1, 1, 3, 4, 4, 6`.

## 3. 스키마

### 3.1 `RoomSummary`

```json
{
  "id": "room_01J7J...",
  "code": "A7K2Q9",
  "name": "마케팅팀 금요 워크숍",
  "status": "OPEN",
  "joinable": true,
  "participant_count": 8,
  "settings": {
    "max_participants": 30
  },
  "active_game": {
    "id": "game_01J7J...",
    "type": "TTF"
  }
}
```

`RoomSummary.status`는 방 수명 주기만 나타낸다. 화면의 게임 단계는 게임 snapshot의 `game.status`를 사용한다. `active_game.type`을 모르는 클라이언트는 임의의 게임 API를 호출하지 않고 지원하지 않는 게임으로 처리한다.

### 3.2 `TtfGameSnapshot`

```json
{
  "version": 17,
  "server_time": "2026-09-05T10:30:00.000Z",
  "room": {
    "id": "room_01J7J...",
    "code": "A7K2Q9",
    "name": "마케팅팀 금요 워크숍",
    "status": "IN_GAME",
    "joinable": false,
    "participant_count": 8,
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
    "ready_count": 8,
    "round_count": 8,
    "current_round_number": 2,
    "settings": {
      "statement_min_length": 5,
      "statement_max_length": 100,
      "voting_duration_seconds": 60,
      "speaker_order": "RANDOM",
      "anonymous_voting": true
    }
  },
  "viewer": {
    "role": "PARTICIPANT",
    "participant_id": "participant_02",
    "nickname": "민준",
    "is_ready": true
  },
  "current_round": {
    "id": "round_02",
    "number": 2,
    "total": 8,
    "speaker": {
      "id": "participant_04",
      "nickname": "수빈"
    },
    "statements": [
      { "id": "statement_07", "content": "나는 사막에서 밤을 보낸 적이 있다.", "display_order": 1 },
      { "id": "statement_09", "content": "나는 한 번도 커피를 마신 적이 없다.", "display_order": 2 },
      { "id": "statement_08", "content": "나는 세 개의 악기를 연주할 수 있다.", "display_order": 3 }
    ],
    "voting_started_at": "2026-09-05T10:29:20.000Z",
    "voting_ends_at": "2026-09-05T10:30:20.000Z",
    "vote_progress": { "completed": 5, "eligible": 7 },
    "my_vote_statement_id": "statement_09"
  }
}
```

`room.active_game.id`와 `game.id`는 같아야 하며, `game`이 속한 방이 아닌 경우 서버는 `404 GAME_NOT_FOUND`로 응답한다.

필드 공개 범위:

| 필드 | `participant` | `host` | `display` |
|---|---:|---:|---:|
| 기본 방/TTF 게임/라운드 정보 | O | O | O |
| `viewer.participant_id`, `my_vote_statement_id` | O | - | - |
| 시작 전 본인 `my_statements` | O | - | - |
| `participants` 목록과 준비/연결 상태 | - | O | - |
| 공개 전 문장 진위 | - | - | - |
| `result` (`RESULT` 이후) | O | O | O |
| `voters` (`anonymous_voting=false`, 공개 이후) | O | O | O |
| `leaderboard` (`FINISHED`) | O | O | O |

`host` snapshot의 `participants` 항목:

```json
{
  "id": "participant_02",
  "nickname": "민준",
  "connection_status": "ONLINE",
  "is_ready": true,
  "score": 1,
  "is_current_speaker": false
}
```

게임 시작 전 participant audience에는 본인이 작성한 문장을 수정할 수 있도록 최상위 `my_statements`를 포함한다. 이 필드는 본인에게만 반환한다.

```json
{
  "my_statements": [
    { "id": "statement_01", "content": "나는 사막에서 밤을 보낸 적이 있다.", "is_fake": false },
    { "id": "statement_02", "content": "나는 커피를 한 번도 마신 적이 없다.", "is_fake": true },
    { "id": "statement_03", "content": "나는 세 개의 악기를 연주할 수 있다.", "is_fake": false }
  ]
}
```

`is_fake`가 공개되는 유일한 사전 공개 예외는 인증된 참가자 본인의 `my_statements`다. host/display/다른 participant 응답에는 포함하지 않는다.

`RESULT` 이후 `current_round.result`:

```json
{
  "fake_statement_id": "statement_09",
  "statements": [
    {
      "id": "statement_07",
      "content": "나는 사막에서 밤을 보낸 적이 있다.",
      "display_order": 1,
      "is_fake": false,
      "vote_count": 1,
      "vote_rate": 14.3
    },
    {
      "id": "statement_09",
      "content": "나는 한 번도 커피를 마신 적이 없다.",
      "display_order": 2,
      "is_fake": true,
      "vote_count": 5,
      "vote_rate": 71.4
    },
    {
      "id": "statement_08",
      "content": "나는 세 개의 악기를 연주할 수 있다.",
      "display_order": 3,
      "is_fake": false,
      "vote_count": 1,
      "vote_rate": 14.3
    }
  ],
  "correct_voter_count": 5,
  "fooled_participant_count": 2,
  "score_changes": [
    { "participant_id": "participant_02", "nickname": "민준", "delta": 1, "total": 2 }
  ]
}
```

익명 투표가 꺼진 방에서는 각 statement result에 다음 선택 필드를 추가할 수 있다.

```json
"voters": [{ "id": "participant_02", "nickname": "민준" }]
```

`FINISHED`의 `leaderboard` 항목:

```json
{
  "participant_id": "participant_02",
  "nickname": "민준",
  "score": 5,
  "rank": 1,
  "is_me": true
}
```

`is_me`는 participant audience에서만 해당 참가자에게 `true`이며 host/display에서는 모두 `false`다.

## 4. Endpoint 요약

### 4.1 범용 방 API

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| `POST` | `/rooms` | 공개 | 방과 선택한 게임을 원자적으로 생성하고 진행자 세션 발급 |
| `GET` | `/rooms/by-code/{code}` | 공개 | 입장 전 방 정보 조회 |
| `POST` | `/rooms/{room_id}/participants` | 공개 | 닉네임으로 참가 및 세션 발급 |
| `DELETE` | `/rooms/{room_id}/participants/{participant_id}` | 진행자 | 시작 전 참가자 내보내기 |
| `POST` | `/rooms/{room_id}/commands/cancel` | 진행자 | 시작 전 방과 활성 게임 취소 |

### 4.2 TTF 게임 API

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| `GET` | `/games/ttf/{game_id}/snapshot` | 역할별 | 현재 TTF 전체 상태 복구 |
| `PUT` | `/games/ttf/{game_id}/participants/me/statements` | 참가자 | 문장 3개 생성/전체 교체 |
| `PUT` | `/games/ttf/{game_id}/rounds/{round_id}/vote` | 참가자 | 투표 등록/변경 |
| `GET` | `/games/ttf/{game_id}/events` | 역할별 | SSE 상태 변경 알림 |
| `POST` | `/games/ttf/{game_id}/commands/start` | 진행자 | 게임 시작 |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/start-voting` | 진행자 | 투표 시작 |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/extend-voting` | 진행자 | 투표 시간 연장 |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/close-voting` | 진행자 | 투표 조기 마감 |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/reveal-result` | 진행자 | 정답 및 점수 공개 |
| `POST` | `/games/ttf/{game_id}/rounds/{round_id}/commands/skip` | 진행자 | 현재 라운드 건너뛰기 |
| `POST` | `/games/ttf/{game_id}/commands/next-round` | 진행자 | 다음 라운드 또는 최종 결과 이동 |
| `POST` | `/games/ttf/{game_id}/commands/pause` | 진행자 | 게임 일시 정지 |
| `POST` | `/games/ttf/{game_id}/commands/resume` | 진행자 | 게임 재개 |
| `POST` | `/games/ttf/{game_id}/commands/finish` | 진행자 | 진행 중 게임 조기 종료 |

## 5. 방 및 참가 API

### 5.1 게임방 생성

`POST /api/v1/rooms`

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
      "anonymous_voting": true
    }
  }
}
```

검증:

- `name`: trim 후 1~40자
- `settings.max_participants`: 정수 2~100
- `game.type`: 현재는 `TTF`만 지원한다. 지원하지 않는 값은 `400 VALIDATION_ERROR`로 거절한다.
- `game.settings.statement_max_length`: 정수 20~200. 최소 길이는 MVP에서 5자로 고정
- `game.settings.voting_duration_seconds`: 정수 15~180
- `game.settings.speaker_order`: `RANDOM` 또는 `JOIN_ORDER`
- `game.settings.anonymous_voting`: boolean

응답 `201 Created`와 진행자 쿠키:

```json
{
  "room": {
    "id": "room_01J7J...",
    "code": "A7K2Q9",
    "join_url": "https://ttf.example/join/A7K2Q9"
  },
  "game": {
    "id": "game_01J7J...",
    "type": "TTF"
  }
}
```

방과 게임은 함께 성공하거나 함께 실패한다. `join_url`에는 방 코드만 포함하고 서명 토큰이나 진행자 권한 정보를 넣지 않는다.

### 5.2 방 코드 조회

`GET /api/v1/rooms/by-code/{code}`

- 대소문자를 구분하지 않고 조회하되 응답 코드는 대문자로 정규화한다.
- 응답: `200 RoomSummary`
- 존재하지 않음: `404 ROOM_NOT_FOUND`
- 만료됨: `410 ROOM_EXPIRED`
- 시작 이후에는 방 정보를 반환하되 `joinable=false`다.

### 5.3 게임 참가

`POST /api/v1/rooms/{room_id}/participants`

```json
{ "nickname": "민준" }
```

검증:

- 닉네임은 trim 후 1~20자다.
- 비교용 닉네임은 앞뒤 공백 제거, 연속 공백 축약, Unicode 정규화, locale에 안전한 case folding을 적용한다.
- 같은 방에서 정규화된 닉네임은 유일하다.
- 방 정원 확인과 닉네임 선점은 원자적으로 처리한다.
- 방이 `OPEN`이고 활성 TTF 게임이 `LOBBY`, `SUBMISSION`, `READY`일 때만 참가할 수 있다. 게임 시작 후에는 관전자 입장으로 전환하지 않고 거절한다.

응답 `201 Created`와 참가자 쿠키:

```json
{
  "room_id": "room_01J7J...",
  "participant_id": "participant_02",
  "game": {
    "id": "game_01J7J...",
    "type": "TTF"
  }
}
```

같은 브라우저의 유효한 참가 세션으로 동일 요청을 재전송하면 기존 참가자를 복구하고 같은 응답을 반환한다.

### 5.4 참가자 내보내기

`DELETE /api/v1/rooms/{room_id}/participants/{participant_id}`

- 해당 방의 진행자 쿠키와 `Idempotency-Key`가 필요하다.
- 방이 `OPEN`이고 활성 TTF 게임이 `LOBBY`, `SUBMISSION`, `READY`일 때만 허용한다.
- 참가자, 해당 게임의 제출 문장, 참가 권한을 제거하고 인원/준비 상태를 재평가한다.
- 이미 제거된 참가자에게 같은 요청을 반복하면 `204 No Content`를 반환한다.
- 게임 시작 후에는 발표 순서와 집계를 보호하기 위해 `409 INVALID_STATE_TRANSITION`으로 거절한다.

응답: `204 No Content`

### 5.5 방 취소

`POST /api/v1/rooms/{room_id}/commands/cancel`

- 해당 방의 진행자 쿠키와 `Idempotency-Key`가 필요하다.
- 방이 `OPEN`이고 활성 TTF 게임이 `LOBBY`, `SUBMISSION`, `READY`일 때만 허용한다.
- 방은 `CLOSED`, TTF 게임은 `CANCELLED`로 같은 트랜잭션에서 전환하고 연결된 클라이언트에 알린 뒤 정리 정책을 시작한다.

응답: `204 No Content`

## 6. TTF 게임 API

아래 endpoint는 반드시 `game.type=TTF`인 `game_id`를 받는다. 다른 유형이나 존재하지 않는 게임은 `404 GAME_NOT_FOUND`로 처리해 게임별 내부 계약이 섞이지 않게 한다.

### 6.1 상태 snapshot

`GET /api/v1/games/ttf/{game_id}/snapshot?audience={audience}`

`audience`:

- `participant`: 게임이 속한 방의 참가자 쿠키 필수
- `host`: 게임이 속한 방의 진행자 쿠키 필수
- `display`: 쿠키 불필요, 항상 공개용 응답

응답: `200 TtfGameSnapshot`

- 새로고침, 첫 연결, SSE 재연결, 이벤트 누락 시 이 endpoint 하나로 현재 화면을 완전히 복구할 수 있어야 한다.
- `server_time`과 `voting_ends_at`을 함께 반환해 클라이언트가 남은 시간을 표시할 수 있게 한다. 최종 마감 판정은 항상 서버가 한다.
- `PAUSED`에서는 `game.paused_from_status`와 현재 라운드를 포함하며 `voting_ends_at`은 재개 후 갱신한다.

### 6.2 문장 저장/수정

`PUT /api/v1/games/ttf/{game_id}/participants/me/statements`

```json
{
  "statements": [
    { "content": "나는 사막에서 밤을 보낸 적이 있다.", "is_fake": false },
    { "content": "나는 커피를 한 번도 마신 적이 없다.", "is_fake": true },
    { "content": "나는 세 개의 악기를 연주할 수 있다.", "is_fake": false }
  ]
}
```

검증:

- 정확히 3개이며 `is_fake=true`가 정확히 1개다.
- 각 `content`는 trim 후 TTF 게임 설정의 최소~최대 길이를 만족해야 한다.
- 연속 공백 축약 및 Unicode 정규화 후 동일한 문장을 중복 제출할 수 없다.
- 원문은 자동 필터링하거나 진위를 판정하지 않는다.
- 게임 시작 전 `LOBBY`, `SUBMISSION`, `READY`에서만 전체 교체할 수 있다.
- 성공 시 참가자의 `is_ready=true`로 만들고 TTF 게임 상태를 재평가한다.
- 공개 순서는 이 요청의 배열 순서와 무관하게 서버에서 한 번 무작위화한다.

응답: `204 No Content`

### 6.3 투표 등록/변경

`PUT /api/v1/games/ttf/{game_id}/rounds/{round_id}/vote`

```json
{ "statement_id": "statement_09" }
```

- 참가자 세션에서 voter를 결정하며 body로 participant ID를 받지 않는다.
- 현재 게임/라운드가 `VOTING`이고 서버 시간이 `voting_ends_at` 전일 때만 성공한다.
- statement는 현재 라운드에 속해야 한다.
- 발표자는 투표할 수 없다.
- `(round_id, voter_participant_id)`를 유일하게 유지하며 기존 표가 있으면 새 statement로 변경한다.
- 같은 statement에 대한 반복 `PUT`은 성공하되 표 수를 늘리지 않는다.

응답: `204 No Content`

마감과 요청이 경합한 경우 서버 트랜잭션에서 마감이 먼저 확정되었다면 `409 VOTING_NOT_OPEN`을 반환한다. 프론트엔드는 이 응답을 받으면 투표가 저장되었다고 표시하지 않는다.

## 7. TTF 진행자 명령 API

모든 endpoint는 게임이 속한 방의 진행자 쿠키와 `Idempotency-Key`를 요구한다. 성공 응답은 모두 `204 No Content`이며 이후 snapshot 또는 SSE로 새 상태를 확인한다.

### 7.1 게임 시작

`POST /api/v1/games/ttf/{game_id}/commands/start`

- 허용 상태: `READY`
- 현재 참가자 2명 이상, 전원 문장 제출 완료 필요
- 설정에 따라 발표 순서를 한 번 정하고 첫 라운드를 `ROUND_INTRO`로 만든다.
- 게임을 `ROUND_INTRO`로 바꾸는 것과 방을 `IN_GAME`으로 잠그는 것을 같은 트랜잭션에서 처리한다.
- 시작 커밋 이후 모든 신규 참가 요청은 `GAME_ALREADY_STARTED`로 거절한다.

### 7.2 투표 시작

`POST /api/v1/games/ttf/{game_id}/rounds/{round_id}/commands/start-voting`

- 허용 상태: `ROUND_INTRO`
- `voting_started_at=server_now`
- `voting_ends_at=server_now + voting_duration_seconds`
- 자동 마감 작업을 예약한다. 여러 서버 인스턴스가 실행해도 한 번만 `VOTE_CLOSED`로 전환되어야 한다.

### 7.3 투표 시간 연장

`POST /api/v1/games/ttf/{game_id}/rounds/{round_id}/commands/extend-voting`

```json
{ "seconds": 15 }
```

- 허용 상태: `VOTING`
- `seconds`: 정수 5~60
- 현재 `voting_ends_at`에 더한다. 기존 자동 마감 작업은 새 시각을 존중해야 한다.

### 7.4 투표 조기 마감

`POST /api/v1/games/ttf/{game_id}/rounds/{round_id}/commands/close-voting`

- 허용 상태: `VOTING`
- 성공 커밋 시점부터 추가 투표를 받지 않는다.
- 자동 마감과 경합해도 한 번만 `VOTE_CLOSED`로 전환한다.

### 7.5 정답 공개

`POST /api/v1/games/ttf/{game_id}/rounds/{round_id}/commands/reveal-result`

- 허용 상태: `VOTE_CLOSED`
- 결과 집계와 정답자 점수 반영을 하나의 원자적 작업으로 수행한다.
- 같은 명령을 반복해도 점수가 중복 반영되지 않는다.
- 커밋 후 상태는 `RESULT`다.

### 7.6 라운드 건너뛰기

`POST /api/v1/games/ttf/{game_id}/rounds/{round_id}/commands/skip`

- 허용 상태: `ROUND_INTRO`
- 해당 라운드는 점수 변화 없이 skipped로 기록하고 다음 라운드를 `ROUND_INTRO`로 연다.
- 마지막 라운드였다면 게임을 `FINISHED`, 방을 `CLOSED`로 전환한다.

### 7.7 다음 라운드

`POST /api/v1/games/ttf/{game_id}/commands/next-round`

- 허용 상태: `RESULT`
- 다음 발표자가 있으면 다음 라운드를 `ROUND_INTRO`로 연다.
- 마지막 라운드였다면 최종 leaderboard를 확정하고 게임을 `FINISHED`, 방을 `CLOSED`로 전환한다.

### 7.8 일시 정지/재개

`POST /api/v1/games/ttf/{game_id}/commands/pause`

- 허용 상태: `ROUND_INTRO`, `VOTING`, `VOTE_CLOSED`, `RESULT`
- 이전 상태를 `paused_from_status`로 기록한다.
- `VOTING` 중이면 서버 기준 남은 시간을 저장하고 자동 마감을 무효화한다.

`POST /api/v1/games/ttf/{game_id}/commands/resume`

- 허용 상태: `PAUSED`
- 이전 상태로 돌아간다.
- 투표 중이었다면 `voting_ends_at=server_now + 저장된 남은 시간`으로 다시 설정하고 자동 마감을 예약한다.

### 7.9 조기 종료

`POST /api/v1/games/ttf/{game_id}/commands/finish`

- 허용 상태: `ROUND_INTRO`, `VOTING`, `VOTE_CLOSED`, `RESULT`, `PAUSED`
- 아직 공개하지 않은 현재 라운드에는 점수를 반영하지 않는다.
- 완료된 라운드의 누적 점수로 leaderboard를 계산하고 게임을 `FINISHED`, 방을 `CLOSED`로 전환한다.

## 8. TTF SSE 실시간 이벤트

### 8.1 연결

`GET /api/v1/games/ttf/{game_id}/events?audience={audience}`

요청:

```http
Accept: text/event-stream
Cache-Control: no-cache
```

인증과 공개 범위는 snapshot의 audience 규칙과 동일하다. 응답 헤더 예시:

```http
Content-Type: text/event-stream
Cache-Control: no-cache, no-transform
Connection: keep-alive
X-Accel-Buffering: no
```

- 15~25초마다 `: heartbeat` 주석을 보내 idle proxy timeout을 방지한다.
- 재연결 권장 간격은 `retry: 3000`으로 알린다.
- 이벤트 `id`는 게임 version과 같거나 재개 가능한 별도 단조 증가 ID다.
- 브라우저가 보내는 `Last-Event-ID` 이후 이벤트를 재전송할 수 있으면 재전송한다.
- 보관 범위를 벗어난 이벤트라면 `game.sync_required`를 보내 snapshot 재조회를 유도한다.

```text
id: 18
event: vote.progress_changed
retry: 3000
data: {"event_id":"evt_01J7K...","room_id":"room_01J7J...","game_id":"game_01J7J...","version":18,"occurred_at":"2026-09-05T10:30:01.000Z"}

```

SSE payload는 변경 알림 역할만 한다. 프론트엔드는 payload를 최종 상태로 사용하지 않고 최신 snapshot을 다시 조회한다. 이 방식은 이벤트 중복·역순·누락 시 정합성을 단순하게 유지한다.

### 8.2 이벤트 목록

| 이벤트 | 발생 시점 |
|---|---|
| `participant.joined` | 참가 커밋 후 |
| `participant.left` | 연결 상태 변경 또는 내보내기 후 |
| `participant.ready_changed` | 문장 제출/수정으로 준비 상태 변경 후 |
| `game.status_changed` | TTF 게임 상태 전환 후 |
| `round.started` | 새 라운드 `ROUND_INTRO` 커밋 후 |
| `voting.started` | 투표 시작/재개 및 마감 시각 확정 후 |
| `vote.progress_changed` | 유효 투표자의 최초 투표로 완료 인원이 바뀐 후 |
| `voting.closed` | 자동 또는 수동 마감 커밋 후 |
| `round.result_revealed` | 정답과 점수 반영 커밋 후 |
| `score.updated` | 점수 반영 후. `round.result_revealed`와 같은 version이어도 됨 |
| `game.finished` | 최종 순위 확정 후 |
| `game.sync_required` | 서버가 이벤트 연속성을 보장할 수 없을 때 |

공통 payload:

```json
{
  "event_id": "evt_01J7K...",
  "room_id": "room_01J7J...",
  "game_id": "game_01J7J...",
  "version": 18,
  "occurred_at": "2026-09-05T10:30:01.000Z"
}
```

이벤트에는 원문 문장, `is_fake`, 투표 statement ID, 참가자 토큰을 넣지 않는다. `vote.progress_changed`에도 개별 voter를 포함하지 않는다.

## 9. Rate limit 권장값

정확한 구현 방식은 백엔드가 결정하지만 최소한 다음 범위를 분리해 제한한다.

| 범위 | 권장 제한 |
|---|---:|
| 방 코드 조회 | IP당 분당 60회 |
| 방 생성 | IP당 분당 10회 |
| 참가 시도 | IP + 방당 분당 20회 |
| 문장 저장 | 참가 세션당 분당 10회 |
| 투표 변경 | 참가 세션당 초당 5회 |
| 진행자 명령 | 진행자 세션당 초당 5회 |
| SSE 연결 | 세션/방당 2개, IP당 합리적 상한 |

이는 초기값이며 100명 부하 테스트와 운영 지표에 따라 조정한다.

## 10. 수명 주기와 삭제

- 영구 게임 기록, 사용자 프로필, 과거 결과 조회 endpoint는 MVP에 없다.
- 활성 게임과 같은 브라우저 재접속 복구에 필요한 데이터만 저장한다.
- TTF의 `FINISHED`·`CANCELLED` 또는 방의 `EXPIRED` 상태는 연결된 클라이언트가 마지막 화면/안내를 받을 짧은 grace period 동안만 조회 가능하게 유지할 수 있다.
- grace period 종료 후 방, 게임, 참가자, 문장, 라운드, 투표, 점수, 세션 및 idempotency record를 함께 삭제한다.
- 정확한 grace period는 배포 설정으로 관리하되 장기 보관 목적으로 늘리지 않는다.

## 11. 프론트엔드 연동 체크리스트

- 쿠키와 credential 포함 요청이 로컬/운영 환경 모두에서 동작한다.
- 방 API 응답의 `game.id`를 게임 API에 사용하며 `room_id`와 혼용하지 않는다.
- 알려지지 않은 `active_game.type`은 TTF 화면으로 열지 않는다.
- snapshot audience별 계약 테스트에서 공개 전 `is_fake`와 투표 상세가 존재하지 않는다.
- 같은 닉네임 동시 입장 시 하나만 성공한다.
- 투표 마감 직전/직후 경합에서 늦은 표가 집계되지 않는다.
- `Idempotency-Key` 재전송으로 방, 점수, 다음 라운드가 중복 생성되지 않는다.
- SSE가 중복·역순으로 도착하거나 끊긴 뒤에도 snapshot version으로 복구한다.
- 진행자 두 탭의 상충 명령 중 먼저 커밋된 유효 전환만 성공한다.
- 참가자와 진행자가 새로고침한 뒤 쿠키만으로 동일 역할과 현재 상태를 복구한다.
- 100명 방에서 일반 API p95 500ms, 상태 전파 p95 1초, 결과 집계 2초 이내를 확인한다.
