# TTF Frontend

모바일 참가자, 진행자, 프로젝터용 공용 화면을 제공하는 React 프론트엔드입니다. 백엔드 계약은 루트의 `docs/api-spec.md`를 따릅니다.

## 실행

```bash
npm install
npm run dev
```

개발 서버는 기본적으로 `/api` 요청을 `http://localhost:8081`으로 프록시합니다. 다른 백엔드 주소를 사용할 때는 `.env.example`을 참고해 `VITE_DEV_API_TARGET`을 설정합니다. 배포 환경에서 API가 다른 origin에 있다면 `VITE_API_BASE_URL`을 지정하고, 백엔드의 credential 포함 CORS와 쿠키 설정을 함께 구성해야 합니다.

## 명령어

```bash
npm run lint
npm run test
npm run build
npm run preview
```

## 라우트

- `/` — 홈 및 방 코드 입력
- `/rooms/new` — 게임방 생성
- `/join/:code` — 닉네임 입력 및 참여
- `/play/:gameId` — 참가자 TTF 게임 화면
- `/host/:gameId` — 진행자 TTF 제어 화면
- `/display/:gameId` — 16:9 TTF 공용 화면

세션 자격 증명은 프론트엔드가 직접 저장하지 않습니다. 백엔드가 발급한 Secure, HttpOnly 쿠키를 `credentials: include`로 사용합니다.

## API 경계

- `src/lib/api/rooms.ts`는 방 생성, 코드 조회, 참가, 내보내기와 취소만 담당합니다.
- `src/lib/api/ttf.ts`는 TTF snapshot, 문장, 투표, 진행 명령과 SSE만 담당합니다.
- 방 생성·참가 응답의 `game.id`를 플레이 라우트와 게임 API에 사용하며 `room.id`를 대신 사용하지 않습니다.
