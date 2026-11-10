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
서버의 프록시 주소로 실제 요청이 들어오는지 네트워크 또는 접근 로그로 확인한다.
입력 검사만 공식 API로 남는다.

## 2026-11-10 운영 확인

- dev: `/opt/lore-dev/.env`에서 스위치를 켰고, 앱 컨테이너에서
  `http://172.18.0.1:8317/v1`로 접속한다. Docker 브리지에서
  `172.18.0.5 → 172.18.0.1:8317` 트래픽을 확인했다.
- prod: `/etc/lore/cliproxy-webtoon.env`를
  `/etc/systemd/system/lore-api.service.d/30-cliproxy-webtoon.conf`에서 읽는다.
  `http://127.0.0.1:8317/v1`로 접속하며 루프백 트래픽을 확인했다.
- 두 서버 모두 CLIProxyAPI는 서버별 Codex OAuth와 클라이언트 키를 사용한다.
  인증 파일은 `/var/lib/cliproxyapi`, 설정은 `/etc/cliproxyapi/config.yaml`에 있다.
- Chrome에서 dev `20261109T165853-5db863`(7쪽)와 prod
  `20261109T171009-37416b`(6쪽)를 끝까지 생성·열람했다. 두 환경 모두
  `별을 잃은 항구, 라미에` 세계관의 AI 텍스트 생성과 실제 화면을 확인했다.
- prod `webtoon_usage`의 전환 후 34건은 모두 `codex_subscription`, 합계
  `cost_usd=0`이었다. prod 관리자 계정의 서비스 내부 크레딧은 웹툰 한 편에
  15C(274→259C)가 차감됐다. 이것은 외부 모델 API 청구액과 다른 장부다.
- `cost_usd=0`은 **LORE 내부 회계 표기**다. OpenAI 공식 청구 내역은 별도
  Admin API 키가 없어 조회가 403으로 거절됐다. 프록시 주소·실제 TCP 연결·
  모델 호출 기록으로 경로를 확인했지만, 공식 청구서의 0원 확정으로 읽지 않는다.
  Codex 잔여량은 [사용량 페이지](https://chatgpt.com/codex/settings/usage) 또는
  Codex CLI `/status`에서 확인한다. 프록시는 비공식 소프트웨어이므로
  구독 사용 가능 범위와 한도는 계속 확인해야 한다.

### 실제 설정 변경·복귀

dev에서는 작업 중인 웹툰이 없는 때에 다음을 서버에서 실행한다.

```bash
sudo sed -i 's/^WEBTOON_CODEX_PROXY=true$/WEBTOON_CODEX_PROXY=false/' /opt/lore-dev/.env
cd /opt/lore-dev/src
sudo docker compose -p lore-dev -f docker-compose.yml -f docker-compose.dev-ec2.yml \
  --env-file /opt/lore-dev/.env up -d --no-deps app
```

prod에서는 최근 방문 이벤트와 진행 중 생성 작업이 모두 0인지 다시 확인한 뒤 실행한다.

```bash
sudo sed -i 's/^WEBTOON_CODEX_PROXY=true$/WEBTOON_CODEX_PROXY=false/' /etc/lore/cliproxy-webtoon.env
sudo systemctl restart lore-api
```

다시 프록시를 쓰려면 위 `sed`의 `true`와 `false`를 바꾸고 같은 명령으로
재기동한다. 키 값은 출력·복사하지 않는다. 운영 세계관 편집의 17KB 본문이
CloudFront WAF의 `Block-LargeBody-Except-PieceMaker`에 막혀, 2026-11-10
`PUT /api/webtoon/v1/play/worlds/*`만 이 규칙의 예외로 추가했다. 관리형 WAF
규칙은 계속 적용된다. 이 예외는 프록시 스위치와 독립적이다.

## 복귀

`WEBTOON_CODEX_PROXY=false`로 바꾸고 app 또는 `lore-api`를 재기동한다.
프록시용 변수는 남아 있어도 이 스위치가 꺼져 있으면 생성 코드가 사용하지 않는다.
`run.py --plan`에서 원래 단계별 모델을 확인하고, 다음 실제 호출의 `model`·
`cost_basis`가 기존 방식으로 기록되는지 확인한다. 기존 `WEBTOON_API_KEY`와
`OPENROUTER_API_KEY`는 전환 과정에서 덮어쓰지 않는다.
