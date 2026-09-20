# API 명세

> 서버 기준: Spring Boot 4.1 · Base URL(로컬) `http://localhost:8080`
> 문서(Swagger UI): `http://localhost:8080/swagger-ui.html` — **엔드포인트가 늘면 Swagger 가 항상 최신입니다.**
> 이 문서는 "프론트가 계약으로 삼을 것"만 적습니다. 마지막 갱신 2026-08-01 (상훈)

---

## 1. 공통 응답 껍데기

도메인과 상관없이 모든 응답이 이 형태입니다.
(`common/be/src/main/java/com/lore/common/response/ApiResponse.java`)

**성공**

```json
{
  "success": true,
  "data": { "...": "엔드포인트마다 다름" },
  "message": null,
  "error": null
}
```

**실패**

```json
{
  "success": false,
  "data": null,
  "message": "입력값이 올바르지 않습니다",
  "error": { "code": "INVALID_INPUT", "message": "입력값이 올바르지 않습니다" }
}
```

**프론트에서 지켜주실 것**

- 성공/실패 판별은 `success` 로.
- 분기가 필요하면 **`error.code`** 로 하세요. `message` 문구는 바뀔 수 있습니다.
- HTTP 상태 코드도 함께 맞춰 내려갑니다 (400 / 500).

---

## 2. 공통 에러 코드

| code | HTTP | 의미 |
| --- | --- | --- |
| `INVALID_INPUT` | 400 | 요청 값이 규칙에 안 맞음 (필수 누락, 길이 초과 등) |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류 (상세 원인은 서버 로그에만 남김) |

> 도메인별 에러는 접두어로 구분합니다. `ZZAL_*` · `WEBTOON_*` · `TRAILER_*`
> 각 담당자가 자기 도메인 코드를 `common/be/.../exception/ErrorCode.java` 에 추가하고
> 이 표에도 한 줄 적어주세요.

---

## 3. 엔드포인트 목록

| 도메인 | Method | Path | 설명 | 담당 |
| --- | --- | --- | --- | --- |
| common | GET | `/actuator/health` | 서버 상태 확인 | 상훈 |
| common | | | 계정/인증 (회원가입·로그인) — **다음 스프린트** | |
| webtoon | | | | 하은 |
| zzal | | | 설계 논의 중 | 상훈 |
| trailer | | | | 병연 |

> 도메인 API 경로에는 `v1` 을 붙입니다. 나중에 응답 형태를 바꿔야 할 때 `v2` 를 새로 열어
> 프론트가 준비되는 대로 옮겨올 수 있게 하기 위함입니다. (예: `/api/v1/...`)

---

## 4. 서버 상태 확인

### `GET /actuator/health`

배포 직후 "서버가 떴는가" 를 확인하는 용도입니다. 일반 API 로 확인하면 확인할 때마다
DB 조회가 발생하므로 별도로 둡니다. 나중에 Nginx · 로드밸런서 · CI/CD 도 이 주소를 씁니다.

- **응답** `200`

  ```json
  { "status": "UP" }
  ```

- 서버 내부 사정(DB 주소 · 디스크 등)이 밖으로 새지 않도록 상세 정보는 감춰둡니다.

---

## 5. 아직 없는 것 (다음 스프린트)

- **인증/로그인** — 지금은 모든 API 가 무인증으로 열려 있습니다. 구글 OAuth 는 다음 스프린트.
- **이미지 업로드/생성** — S3 연동은 R&D 결과가 나온 뒤 붙입니다.
- **페이징 · 정렬 옵션** — 목록 API 가 늘기 전에 규격을 먼저 정합니다.
- **도메인별 API** — zzal / webtoon / trailer 는 각 담당자가 설계 확정 후 이 문서에 추가합니다.
