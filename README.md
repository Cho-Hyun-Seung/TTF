# TTF (Truth Truth Fake)

QR 코드로 참가하는 실시간 진진가 플랫폼입니다. 진행자가 게임방을 만들면 참가자는 별도 회원가입이나 앱 설치 없이 모바일 웹으로 참여할 수 있습니다.

## 저장소 구조

```text
ttf/
├── frontend/        React + TypeScript 프론트엔드
├── backend/         백엔드 구현 디렉터리
├── docs/            API 및 기술 문서
└── platformprd.md   제품 요구사항
```

## Docker Compose 실행

Docker가 설치된 환경에서 프론트엔드와 백엔드를 함께 빌드하고 실행합니다.

```bash
docker compose up --build
```

- 프론트엔드: `http://localhost:5173`
- 백엔드: `http://localhost:8081`

종료할 때는 `docker compose down`을 실행합니다. 포트나 외부 접속 주소를 바꾸려면 루트의 `.env.example`을 `.env`로 복사한 뒤 값을 수정하세요. 다른 기기에서 QR로 참여할 때는 `TTF_FRONTEND_BASE_URL`과 `TTF_CORS_ALLOWED_ORIGINS`를 해당 기기에서 접근 가능한 프론트엔드 URL로 설정해야 합니다.

현재 백엔드는 게임 상태를 메모리에 저장하므로 백엔드 컨테이너를 재시작하면 진행 중인 게임도 초기화됩니다.

## 프론트엔드 실행

```bash
cd frontend
npm install
npm run dev
```

개발 서버는 기본적으로 백엔드 `http://localhost:8081`으로 `/api` 요청을 프록시합니다. 자세한 내용은 [프론트엔드 README](frontend/README.md)를 참고하세요.

## 문서

- [제품 요구사항](platformprd.md)
- [API 명세](docs/api-spec.md)
- [API request record 명세](docs/api-request-records.md)
- [API response record 명세](docs/api-response-records.md)
- [저장소 작업 규칙](AGENTS.md)

## 검증

```bash
cd frontend
npm run lint
npm run test
npm run build
```
