# 크레딧 충전 결제 (페이앱) — 켜는 법과 확인 순서

크레딧 충전을 실제로 켤 때, 그리고 결제가 이상할 때 읽습니다. 코드 설명은
`common/be/src/main/java/com/lore/common/payment/PaymentService.java` 머리말에 있습니다(#155).

## 한눈에

- 결제대행: **페이앱**(주식회사 유디아이디). 개인판매자로 가입, 카드 수수료 4.0%(+부가세), 정산 D+3~5
- **테스트 환경이 없습니다.** 연동값을 넣은 서버는 그 순간부터 실제로 결제를 받습니다
- 연동값이 없는 서버는 충전 창이 「준비 중」 + 1:1 문의 안내로 보입니다(지금 모든 환경이 이 상태)
- 크레딧은 **페이앱 → 서버 통보로만** 들어갑니다. 통보 주소는 `https://<사이트>/api/v1/payments/payapp/feedback`

## 1. 페이앱 쪽 준비 (한 번)

1. 판매자 가입 — 개인판매자, 서비스 구분 「컨텐츠」
2. 가입 다음 영업일에 오는 **전자계약서** 작성 + 신분증 앞뒤 + 본인 명의 통장사본 제출
3. 심사 완료 전화를 받은 뒤에 켭니다. 심사 전에 실제 사용자가 결제하면, 업종 심사에서
   걸렸을 때 그 결제가 취소됩니다
4. 판매자 관리 사이트 → **설정 → 연동 정보** 에서 연동 KEY · 연동 VALUE 확인

업종 기준표(2026-10-13 확인): 불가 업종에 해당 없음. 크레딧이 유의 업종 「사이버머니(10만원 초과)」로
읽힐 수 있으나 최고가 38,900원이라 기준 아래입니다.

## 2. 서버에 넣는 값 (네 개)

| 이름 | 값 | 비고 |
| --- | --- | --- |
| `PAYAPP_USERID` | 페이앱 판매자 아이디 | |
| `PAYAPP_LINKKEY` | 연동 KEY | **비밀값** |
| `PAYAPP_LINKVAL` | 연동 VALUE | **비밀값.** 통보가 진짜 페이앱에서 왔는지 가리는 값이라 어디에도 보이면 안 됩니다 |
| `PAYAPP_SITE_URL` | 그 서버의 바깥 주소 | dev `https://dev.lorecomic.com` · staging `https://staging.lorecomic.com` · prod `https://lorecomic.com` |

넷 중 하나라도 비면 결제창을 안 엽니다. 채팅·커밋·이슈에 KEY·VALUE 를 붙여 넣지 않습니다.

### 환경별로 넣는 곳

| 환경 | 넣는 곳 | 반영 |
| --- | --- | --- |
| dev | 박스 `/opt/lore-dev/.env` 에 네 줄. `docker-compose.yml` 의 `environment:` 통로는 이미 열려 있습니다 | 앱 컨테이너 재생성. **app 만 재시작하면 IP 가 바뀌어 nginx 가 502** — nginx 도 같이 재생성하거나 Actions 로 다시 배포 |
| staging | Parameter Store `/lore/staging/payapp_userid` · `payapp_linkkey` · `payapp_linkval` · `payapp_site_url` (SecureString) | 다음 배포 또는 `lore-api` 재시작 때 `load-secrets.sh` 가 대문자 이름으로 내려받습니다 |
| prod | Parameter Store `/lore/prod/` 아래 같은 이름 네 개 | 위와 같음 |

### 켜졌는지 확인

```
curl -s https://dev.lorecomic.com/api/v1/payments/packages
# "ready":true 면 켜진 것. false 면 넷 중 하나가 비었다
```

## 3. 첫 실결제 확인 (환경마다 한 번)

결제 금액이 실제로 빠지니, 가장 싼 15C(4,900원)로 하고 바로 취소합니다.

1. 로그인 → 마이페이지 → 크레딧 「충전」 → **15C** → 본인 휴대폰 번호 → 결제하기
2. 새 창의 페이앱 결제창에서 카드로 결제
3. 원래 창에 **「15C 충전됐어요」** 가 뜨고 잔액이 15 늘었는지 봅니다
4. DB 로 확인합니다(dev 예시, `webtoon/tools/examples/db-sql.sh` 는 기본이 읽기 전용).

   ```
   webtoon/tools/examples/db-sql.sh dev "select order_no,status,amount,pay_type,paid_at from payment_order order by id desc limit 3"
   webtoon/tools/examples/db-sql.sh dev "select delta,reason,ref_id,memo,created_at from credit_event where ref_id like 'payapp%' order by id desc limit 5"
   ```

   `PAID` 한 줄, 장부에 `+15 PURCHASE payapp-<주문번호>` 한 줄이 있어야 합니다.
5. 페이앱 판매자 사이트 → 결제내역 → 그 건 **결제취소**
6. 몇 초 안에 주문이 `REFUNDED`, 장부에 `-15 ADJUST payapp-cancel-<주문번호>` 가 생기고 잔액이 원래대로 돌아왔는지 봅니다

여기까지 되면 그 환경은 켜진 것입니다.

## 4. 환불할 때

- 이용자가 환불을 요청하면 **페이앱 판매자 사이트에서 그 결제를 취소**합니다. 통보가 오면 서버가
  충전분을 자동으로 회수합니다(충전분을 이미 썼으면 잔액이 0 아래로 내려가고, 그동안은 만들기가 막힙니다).
- **부분 취소**는 주문에 `PARTIAL_REFUNDED` 만 표시합니다. 크레딧은 관리자가 손으로 맞춥니다.
- 정산이 끝난 건은 페이앱에서 바로 취소가 안 되고 「환불 요청」 절차가 따로 있습니다(`paycancelreq`).

## 5. 이상할 때

| 증상 | 볼 곳 |
| --- | --- |
| 「결제창을 열지 못했어요」 | 서버 로그 `페이앱 결제요청 실패` 줄의 msg. `권한이 없습니다` 면 USERID 가 틀렸거나 심사 전 |
| 결제는 됐는데 크레딧이 안 들어옴 | 서버 로그 `페이앱 통보 —` 로 시작하는 줄. ① 줄이 아예 없으면 통보가 서버에 안 닿은 것(SITE_URL 이 틀렸거나 https 가 아님) ② `연동값이 맞지 않아` 면 LINKKEY/LINKVAL 오타 ③ `금액이 다릅니다` 면 결제 금액과 주문 금액이 다른 것 |
| 같은 결제로 두 번 들어옴 | 일어나면 안 됩니다. 장부 유일키(`payapp-<주문번호>`)가 막습니다. 생기면 바로 이슈로 |

페이앱은 통보에 `SUCCESS` 를 못 받으면 최대 10번 다시 보냅니다(`checkretry=y`). 서버를 고치고
다시 띄우면 밀린 통보가 들어와 크레딧이 들어갑니다.

페이앱 고객센터: 1800-3772 · payapp@udid.co.kr · [1:1 문의](https://payapp.kr/homepage/bbs/bbs_ask.html)
개발 문서: https://docs.payapp.kr (전체 원문 `llms-full.txt`)

## 6. 아직 안 정한 것

- 가격 확정(지금 값은 자리표시자)
- 이용약관·개인정보처리방침 게시 — 작업본(`webtoon/docs/legal/`)에 초안만 넣어 두었습니다.
  게시하려면 판 번호를 올리고 사전 공지를 거쳐야 합니다
- 미사용 유료 크레딧의 유효기간, 유료·무료 크레딧 중 무엇부터 쓰는지
