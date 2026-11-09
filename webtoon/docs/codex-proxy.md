# 웹툰 Codex 프록시 전환과 복귀

웹툰·캐릭터·세계관 생성 파이썬만 서버 로컬 CLIProxyAPI로 보내는 선택적 설정이다.
`WEBTOON_CODEX_PROXY`의 기본값은 꺼짐이다. 켜면 텍스트 모델은
`CODEX_PROXY_TEXT_MODEL`(기본 `gpt-6-sol`), 이미지 모델은 기존 단계별
`gpt-image-2`·`gpt-image-2.5-flare`를 쓴다. 기존 `WEBTOON_API_KEY`는
무료 입력 검사(moderation)에만 남긴다. 짤·다른 서비스 호출은 대상이 아니다.

## 서버 설정

- 각 서버의 CLIProxyAPI는 별도 Codex OAuth를 갖고, 클라이언트 키를 요구한다.
- dev는 Docker 호스트의 브리지 주소에서만 프록시를 듣는다. dev `app` 컨테이너에는
  `docker-compose.yml`이 아래 네 변수를 전달한다. `.env`의 실제 키는 비공개다.
- prod는 `lore-api`의 별도 systemd `EnvironmentFile`에서 같은 변수를 받는다.
  기본 API 키와 OAuth 인증 파일을 저장소나 SSM 명령 출력에 넣지 않는다.

```
WEBTOON_CODEX_PROXY=true
CODEX_PROXY_BASE_URL=http://<서버에서 접근 가능한 프록시 주소>:8317/v1
CODEX_PROXY_API_KEY=<해당 서버의 프록시 클라이언트 키>
CODEX_PROXY_TEXT_MODEL=gpt-6-sol
```

새 프로세스가 환경값을 받아야 하므로 작업이 없는 때에 app 또는 `lore-api`를
재기동한다. `run.py --plan`에서 텍스트 단계가 `openai:gpt-6-sol`, 이미지 단계가
기존 이미지 모델인지 확인한다. 실제 생성 후 `meta.json`과 `webtoon_usage`의
`model`, `cost_basis=codex_subscription`, `cost_usd=0`을 대조한다.
프록시 요청 로그와 Codex 사용량도 함께 확인한다. 입력 검사만 공식 API로 남는다.

## 복귀

`WEBTOON_CODEX_PROXY=false`로 바꾸고 app 또는 `lore-api`를 재기동한다.
프록시용 변수는 남아 있어도 이 스위치가 꺼져 있으면 생성 코드가 사용하지 않는다.
`run.py --plan`에서 원래 단계별 모델을 확인하고, 다음 실제 호출의 `model`·
`cost_basis`가 기존 방식으로 기록되는지 확인한다. 기존 `WEBTOON_API_KEY`와
`OPENROUTER_API_KEY`는 전환 과정에서 덮어쓰지 않는다.
