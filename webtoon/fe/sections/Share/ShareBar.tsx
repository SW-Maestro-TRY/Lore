"use client";

import { useEffect, useState } from "react";
import {
  copyLink, shareNative, SHARE_TARGETS, shareUrl,
} from "../../lib/share";

/* 「공유하기」 한 줄.
 *
 * **누르면 바로 목록이 뜨지 않는다.** 폰이 자기 공유 화면을 들고 있으면
 * 그것을 먼저 띄운다 — 그 사람이 실제로 쓰는 앱이 다 뜨고, 우리가 고른 넷보다
 * 훨씬 많다. 없을 때만(대개 PC) 우리 목록을 편다.
 *
 * 어디에 붙어도 되게 만들었다. 지금은 완성본과 편집실 둘이 쓴다 — 만들고 나온
 * 사람이 제일 보내고 싶은 순간이 그 두 자리다.
 */
export default function ShareBar({
  runId,
  episode = 1,
  title,
  character,
}: {
  runId: string;
  episode?: number;
  /** 보낼 때 함께 나갈 말. 편집실처럼 제목을 모르는 자리에서는 안 준다. */
  title?: string;
  character?: string;
}) {
  const [open, setOpen] = useState(false);
  const [said, setSaid] = useState("");

  /* 알림 한 줄은 스스로 사라진다. 안 사라지면 화면에 계속 남아서, 나중에 다시
     눌렀을 때 이번에 된 것인지 아까 것인지 알 수 없다. */
  useEffect(() => {
    if (!said) return;
    const timer = window.setTimeout(() => setSaid(""), 2400);
    return () => window.clearTimeout(timer);
  }, [said]);

  const url = shareUrl(runId, episode);
  /* 링크에 붙는 한 줄. 제목을 모르는 자리(편집실)에서는 작품 id 대신 이걸
     쓴다 — id 를 보내면 받는 사람에게 아무 뜻도 없는 글자가 간다. 링크를 열면
     서버가 제목과 표지를 붙여 주므로(og_tags) 정작 카드에는 제대로 뜬다. */
  const text = title
    ? (character ? `${character} · ${title}` : title)
    : "LORE 로 만든 웹툰";

  const start = async () => {
    if (await shareNative(url, text)) return;   // 폰이 알아서 한다
    setOpen((was) => !was);
  };

  const pick = async (key: string, href?: (u: string, t: string) => string) => {
    // 어디로 보내든 링크를 먼저 복사해 둔다. 붙여넣기로 끝나는 곳(포스타입)이
    // 있고, 나머지에서도 복사돼 있어서 손해 보는 일이 없다.
    const copied = await copyLink(url);
    if (!href) {
      setSaid(copied ? "링크를 복사했어요" : "복사하지 못했어요 — 주소창을 그대로 쓰세요");
      setOpen(false);
      return;
    }
    // 새 창으로 연다. 이 창을 바꿔 버리면 읽던 자리를 잃는다.
    window.open(href(url, text), "_blank", "noopener,noreferrer");
    setSaid(key === "postype" ? "링크를 복사했어요 — 글에 붙여 넣으세요" : "");
    setOpen(false);
  };

  return (
    <span className="share">
      {/* 제목 옆에 서는 아이콘 하나. 「공유하기」라고 적힌 단추를 제목 아래
          줄에 두면 그 줄이 통째로 단추줄이 되어, 읽으러 온 사람 눈에 제목
          다음으로 큰 것이 「공유하기」가 된다. 아이콘은 제목의 일부처럼
          붙어 서서 필요한 사람만 찾는다.
          그림만 두면 무엇인지 모르는 사람이 있으므로 이름은 남긴다(읽어
          주는 기계와 마우스를 올린 사람 둘 다에게). */}
      <button type="button" className="share-icon" onClick={start}
              aria-expanded={open} aria-label="공유하기" title="공유하기">
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"
             fill="none" stroke="currentColor" strokeWidth="1.9"
             strokeLinecap="round" strokeLinejoin="round">
          <circle cx="18" cy="5" r="3" />
          <circle cx="6" cy="12" r="3" />
          <circle cx="18" cy="19" r="3" />
          <path d="M8.6 10.6 15.4 6.4M8.6 13.4l6.8 4.2" />
        </svg>
      </button>

      {open && (
        <span className="share-list">
          {SHARE_TARGETS.map((t) => (
            <button key={t.key} type="button" className="share-one"
                    onClick={() => pick(t.key, t.href)}>
              {t.label}
            </button>
          ))}
        </span>
      )}
      {said && <span className="share-said" role="status">{said}</span>}
    </span>
  );
}
