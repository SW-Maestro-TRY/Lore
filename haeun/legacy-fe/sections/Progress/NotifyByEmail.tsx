"use client";

import { useEffect, useState } from "react";
import { notifyByEmail } from "../../lib/nhApi";
import type { NhJob } from "../../lib/nhApi";

/* 다 되면 메일로 알려 주는 자리.
 *
 * **왜 있는가.** 한 편에 5~15분이 걸린다. 그동안 이 화면이 사람을 붙들고
 * 있었고, 우리가 해 준 말은 「나갔다 와도 이어집니다」뿐이었다 — 언제
 * 돌아와야 하는지는 안 알려 줬다. 그래서 사람은 진행 막대를 보며 앉아
 * 있거나, 나갔다가 영영 안 돌아온다.
 *
 * 게스트는 더 나쁘다. 자기 작품을 브라우저 uid 로만 찾으므로 **다른 기기로
 * 들어오면 만든 것을 못 찾는다.** 메일에 담기는 결과 링크가 그 사람이 자기
 * 작품으로 돌아오는 유일한 길이다.
 *
 * **로그인 여부를 화면이 판단하지 않는다.** 서버가 `notice.logged_in` 과
 * 실제로 보낼 주소(`notice.email`)를 같이 준다 — 화면이 짐작하면 두 화면이
 * 갈리고, 계정 이메일을 바꾼 사람에게 옛 주소를 보여 주게 된다.
 */
export default function NotifyByEmail({ jobId, job }: { jobId: string; job: NhJob }) {
  const notice = job.notice;
  /* 서버가 이 칸을 안 보내는 동안(옛 서버·프록시 길)에는 통째로 안 그린다 —
     없는 기능의 입력 칸을 띄우면 적어 넣고 영영 못 받는다. */
  const [typed, setTyped] = useState("");
  const [saved, setSaved] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);

  /* 서버가 아는 주소가 이 화면의 진실이다. 새로고침하거나 다른 탭에서
     적어 넣었어도 여기로 따라온다. */
  const known = saved ?? notice?.email ?? null;

  useEffect(() => {
    if (notice?.email) setSaved(notice.email);
  }, [notice?.email]);

  if (!notice) return null;

  /* **남은 시간은 서버가 센다.** 화면이 자기 시계로 세면 새로고침할 때마다
     값이 뛴다. 사람이 답할 차례이거나 끝났으면 서버가 안 준다 — 그때
     멈춰 있는 것은 우리가 아니라 그 사람이라 적으면 거짓말이 된다.

     **권유와 붙여 쓰지 않고 줄을 바꾼다.** 「…보여드릴게요! 지금 약 3분
     남았어요.」로 이어 놓으면 한 덩어리로 읽혀서 숫자가 안 보인다. 기다릴지
     나갈지를 정하는 값이라 제 줄을 준다. */
  const left = job.minutes_left
    ? <span className="notify-left">지금 약 {job.minutes_left}분 남았어요.</span>
    : null;

  const save = async (value: string) => {
    setBusy(true);
    setFailed(null);
    try {
      const got = await notifyByEmail(jobId, value);
      setSaved(got.email ?? null);
      setTyped("");
    } catch (e) {
      setFailed((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  /* ---- 이미 받을 데가 있는 경우 ----
     로그인한 사람은 처음부터 여기다 — 안 묻는다. 게스트도 한 번 적으면
     여기로 온다. 주소를 그대로 보여 주는 이유: 가려 놓으면 오타를 냈는지
     확인할 길이 없다. */
  if (known) {
    return (
      <div className="notify-mail is-set" role="status">
        <p className="notify-say">
          완성되면 <b>{known}</b> 으로 알림을 드릴게요.
          {left}
        </p>
        <p className="notify-sub">
          창을 닫으셔도 괜찮아요 — 메일의 링크로 다시 오실 수 있어요.
          {!notice.logged_in && !notice.sent && (
            <>
              {" "}
              <button type="button" className="linklike" disabled={busy}
                      onClick={() => void save("")}>
                알림 받지 않기
              </button>
            </>
          )}
        </p>
        {failed && <p className="notify-bad" role="alert">{failed}</p>}
      </div>
    );
  }

  /* ---- 게스트에게 묻는 경우 ----
     **안 적어도 만들기는 그대로 돈다.** 이건 선택이지 조건이 아니라서,
     입력 칸을 막아 세우지 않고 진행 화면 한쪽에 둔다. */
  return (
    <form
      className="notify-mail"
      onSubmit={(e) => { e.preventDefault(); if (typed.trim()) void save(typed); }}
    >
      <label className="notify-say" htmlFor="notifyEmail">
        이메일을 입력해 주시면 완성되면 결과물을 보여드릴게요!
        {left}
      </label>
      <div className="notify-row">
        <input
          id="notifyEmail"
          type="email"
          inputMode="email"
          autoComplete="email"
          placeholder="you@example.com"
          value={typed}
          disabled={busy}
          onChange={(e) => { setTyped(e.target.value); setFailed(null); }}
        />
        <button type="submit" className="btn btn-primary btn-sm"
                disabled={busy || !typed.trim()}>
          {busy ? "저장 중…" : "알림 받기"}
        </button>
      </div>
      {/* 넣은 주소로 무엇을 할 것인지 그 자리에서 밝힌다. 안 적으면 이 줄이
          없는 것과 같은데, 없으면 사람은 최악을 가정한다. */}
      <p className="notify-sub">
        이 작품의 알림에만 써요. 광고는 보내지 않아요. 안 적으셔도 만들기는 그대로 진행돼요.
      </p>
      {failed && <p className="notify-bad" role="alert">{failed}</p>}
    </form>
  );
}
