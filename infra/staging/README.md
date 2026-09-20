# staging 서버 운영

`staging` 브랜치가 배포되는 서버(EC2 1대 + RDS 1대)를 움직이는 스크립트를 모아 둔 자리다.
여기 있는 `.sh` 는 전부 **배포마다 CI 가 `/opt/lore/` 로 복사한다.** 서버에 들어가 직접 고쳐도
다음 배포에서 레포 판본으로 덮어쓰인다. 고칠 일이 있으면 이 폴더를 고친다.

## 1. 전원 사이클 — staging 은 기본이 꺼짐이다

staging 은 하루 종일 켜 둘 이유가 없는 서버다. 그래서 **아무도 안 쓰면 꺼져 있는 것이 정상**이고,
배포가 들어오면 그때 깨어난다.

```
배포 시작 → RDS 깨우기 → EC2 깨우기 → 배포 → 60분 뒤 자동으로 둘 다 꺼짐
```

| 단계 | 누가 | 무엇을 |
|---|---|---|
| 깨우기 | GitHub Actions `wake` 잡 | RDS 가 `stopped` 면 시작하고 `available` 까지 기다린다. EC2 가 `stopped` 면 시작하고 SSM 이 붙을 때까지 기다린다 |
| 배포 | `backend` · `frontend` 잡 | 기존과 같다 |
| 재우기 예약 | `power` 잡 | 박스에서 `schedule-power-off.sh 60` 을 돌린다 |
| 재우기 | 박스의 systemd 타이머 | 60분 뒤 `power-off.sh` 가 RDS 를 세우고 박스를 끈다 |

이미 켜져 있으면 깨우기 단계는 아무것도 하지 않고 넘어간다.
**한 시간 안에 배포가 또 들어오면 카운트다운은 그 시점부터 다시 60분이 된다** — 예약을 새로 걸기 때문이다.

## 2. 스크립트

| 파일 | 하는 일 |
|---|---|
| `deploy-api.sh` | S3 에서 jar 를 받아 `/opt/lore/lore.jar` 를 교체하고 `lore-api` 를 재시작한다. 60초 안에 헬스체크가 안 되면 이전 jar 로 되돌린다 |
| `deploy-web.sh` | S3 에서 Next.js standalone 번들을 받아 `/opt/lore/web` 을 교체하고 `lore-web` 을 재시작한다. 실패하면 이전 번들로 되돌린다 |
| `power-off.sh` | RDS 를 세우고 `shutdown -h now` 로 박스를 끈다 |
| `schedule-power-off.sh <분>` | N분 뒤 `power-off.sh` 를 돌리는 systemd 일회성 타이머를 건다. 기존 예약은 지우고 새로 건다 |
| `cancel-power-off.sh` | 걸려 있는 예약을 취소한다 |

`setup-python.sh` 는 dev·staging 이 같은 것을 쓰므로 한 칸 위 `infra/setup-python.sh` 하나만 둔다.
CI 의 `backend` 잡이 그 파일을 S3 에 올리고 `deploy-api.sh` 가 받아 쓴다 — 여기에 사본을 두지 않는다.

아직 박스에만 있는 것이 하나 남아 있다. `/opt/lore/load-secrets.sh` 는 SSM Parameter Store 의
`/lore/{env}/` 값을 읽어 `/etc/lore/secrets.env` 를 만들고 `lore-api.service` 가 기동 때마다 부른다.
비밀값을 담지 않고 가져오기만 하는 파일이지만 이름 때문에 비밀 파일 검사에 걸려 레포에 넣지 못했다.
CI 의 스크립트 동기화는 이 파일을 건드리지 않으므로 박스의 것이 그대로 쓰인다.

## 3. 서버를 계속 켜 두고 싶을 때

배포 직후 60분 예약이 걸려 있다. 더 오래 붙잡아 두려면 **예약을 취소**한다.

콘솔에서 EC2 → 인스턴스 선택 → 연결 → **Session Manager** 로 붙어서

```bash
sudo /opt/lore/cancel-power-off.sh
```

취소한 뒤로는 다음 배포가 들어올 때까지 아무것도 서버를 끄지 않는다.
다 쓰고 나면 직접 꺼 주거나, 다시 예약을 건다.

```bash
sudo /opt/lore/schedule-power-off.sh 60   # 60분 뒤로 다시 예약
sudo /opt/lore/power-off.sh               # 지금 바로 끄기
systemctl list-timers --all lore-power-off.timer   # 지금 예약이 걸려 있는가
```

타이머는 파일로 남지 않는 일회성 유닛이라 **박스가 꺼졌다 켜지면 예약도 같이 사라진다.**
켜 둔 채 잊어버려서 한밤중에 꺼지는 일은 없다.

## 4. 배포 없이 손으로 켜기

순서가 있다 — **RDS 를 먼저 켜고 EC2 를 켠다.** 반대로 하면 API 가 DB 를 못 찾아
`Restart=always` 로 몇 분간 재시작을 반복하다가 DB 가 올라온 뒤에야 자리를 잡는다.

1. RDS 콘솔 → `lore-staging-db` → 작업 → **시작**. `available` 까지 보통 5~10분
2. EC2 콘솔 → `lore-staging` → 인스턴스 시작. 부팅은 1~2분
3. 다 뜨면 <https://staging.lorecomic.com>

퍼블릭 IP 는 탄력적 IP 라 껐다 켜도 바뀌지 않는다. DNS 는 손볼 것이 없다.

## 5. 알아 둘 것 — RDS 는 7일이 지나면 저절로 다시 켜진다

AWS 의 RDS 정지는 **최대 7일까지만 유지된다.** 7일이 지나면 AWS 가 인스턴스를 자동으로 시작한다.
한 주 넘게 staging 에 배포가 없으면 DB 가 혼자 깨어나 요금이 붙기 시작하므로,
오래 쉴 예정이면 콘솔에서 다시 정지시키거나 스냅샷을 뜨고 삭제하는 쪽을 검토한다.
EC2 에는 이런 제한이 없다 — 정지 상태는 계속 유지된다.
