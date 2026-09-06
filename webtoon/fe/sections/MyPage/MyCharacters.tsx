"use client";

import { useEffect, useState } from "react";
import { listCharacters, type Character } from "../../lib/charApi";

/* 마이페이지의 「내 캐릭터」 줄.
 *
 * **작품보다 먼저 둔다.** 웹툰은 캐릭터로 만드는 것이라, 여기 들어온 사람이
 * 다음에 할 일은 대개 "저 캐릭터로 하나 더" 다.
 *
 * 옆으로 넘겨 본다 — 세로로 쌓으면 캐릭터가 늘수록 작품 목록이 한참 밀린다.
 * 만든 것이 없으면 이 줄을 아예 안 그린다: 빈 칸을 하나 더 보여 줄 이유가 없다. */
export default function MyCharacters({ onOpen }: { onOpen: () => void }) {
  const [mine, setMine] = useState<Character[] | null>(null);

  useEffect(() => {
    let alive = true;
    listCharacters()
      .then((v) => { if (alive) setMine(v.characters.filter((c) => c.mine)); })
      .catch(() => { /* 못 받아 오면 이 줄을 안 그린다 */ });
    return () => { alive = false; };
  }, []);

  if (!mine || mine.length === 0) return null;

  return (
    <section className="mypage-chars">
      <div className="mypage-chars-head">
        <h3>내 캐릭터 {mine.length}</h3>
        <button type="button" className="btn btn-quiet btn-sm" onClick={onOpen}>
          모두 보기
        </button>
      </div>
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
    </section>
  );
}
