"use client";

import { useEffect, useState } from "react";
import { listCharacters, type Character } from "../../lib/charApi";

/* 마이페이지의 「내 캐릭터」 칸.
 *
 * **제목을 스스로 안 단다.** 레일에서 캐릭터를 고르고 들어오는 자리라, 본문
 * 머리(`me-top`)가 이미 「내 캐릭터」라고 말한다. 여기서 h3 를 또 달면 같은
 * 제목이 두 줄 겹친다.
 *
 * 옆으로 넘겨 본다 — 세로로 쌓으면 캐릭터가 늘수록 아래가 한참 밀린다. */
export default function MyCharacters({ onOpen }: { onOpen: () => void }) {
  const [mine, setMine] = useState<Character[] | null>(null);

  useEffect(() => {
    let alive = true;
    listCharacters()
      .then((v) => { if (alive) setMine(v.characters.filter((c) => c.mine)); })
      .catch(() => { /* 못 받아 오면 이 줄을 안 그린다 */ });
    return () => { alive = false; };
  }, []);

  // 못 받아 왔을 때만 안 그린다. **없는 것과 모르는 것은 다르다** —
  // 없으면 만들러 가는 길을 보여줘야 하고, 모르면 아무 말도 안 하는 게 맞다.
  if (!mine) return null;

  return (
    <section className="me-chars">
      {/* **없다고 이 줄을 지우지 않는다.** 지우면 캐릭터라는 것이 있는 줄도
          모른 채로 웹툰만 만들게 된다 — 여기가 그것을 알리는 자리다. */}
      {mine.length === 0 && (
        <button type="button" className="mychar-blank" onClick={onOpen}>
          <b>+</b>
          {/* 칸 전체가 눌리는 단추다 — 안에 「만들러 가기 →」를 또 두면 그것만
              눌러야 하는 줄 알고, 두 줄이 서로 밀려 줄바꿈도 어그러졌다. */}
          <span>
            <strong>아직 만든 캐릭터가 없어요</strong>
            <small>캐릭터를 만들어 두면 웹툰을 만들 때마다 다시 적지 않아도 돼요.</small>
          </span>
        </button>
      )}
      {mine.length > 0 && (
      <ul className="mychar-strip">
        {mine.map((c) => (
          <li key={c.id}>
            <button type="button" className="mychar" onClick={onOpen}>
              {c.art_url
                // eslint-disable-next-line @next/next/no-img-element
                ? <img src={c.art_url} alt={c.name} loading="lazy" />
                : <span className="mychar-none">
                    {c.status === "drawing" ? "그리는 중…" : "그림 없음"}
                  </span>}
              <b>{c.name}</b>
            </button>
          </li>
        ))}
      </ul>
      )}
    </section>
  );
}
