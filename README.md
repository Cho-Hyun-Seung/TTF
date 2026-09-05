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

## 프론트엔드 실행

```bash
cd frontend
npm install
npm run dev
```

개발 서버는 기본적으로 백엔드 `http://localhost:8080`으로 `/api` 요청을 프록시합니다. 자세한 내용은 [프론트엔드 README](frontend/README.md)를 참고하세요.

## 문서

- [제품 요구사항](platformprd.md)
- [API 명세](docs/api-spec.md)
- [저장소 작업 규칙](AGENTS.md)

## 검증

```bash
cd frontend
npm run lint
npm run test
npm run build
```

