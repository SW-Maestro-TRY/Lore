"use client";

import { useCallback, useEffect, useState } from "react";
import {
  listCharacters, removeCharacter, type Character, type CharacterList,
} from "../../lib/charApi";
import CharacterMake from "./CharacterMake";

/* 캐릭터 탭 — **웹툰보다 먼저 있는 자리.**
 *
 * 지금까지 캐릭터는 웹툰 한 편을 만들 때 스쳐 지나가는 입력이었다. 사진과
 * 설명을 적어 넣으면 그 편에 쓰이고 끝이라, 다음 편에 같은 캐릭터를 쓰려면
 * 처음부터 다시 적어야 했다. 여기서 순서가 뒤집힌다 — 캐릭터를 만들어 두고,
 * 그 캐릭터로 웹툰을 만든다.
 *
 * 목록이 비어 있어도 **막다른 길이 아니다.** 기본 제공 캐릭터가 같이 보이고,
 * 만들 것이 없으면 바로 만들 수 있다. */
export default function Characters({ onUse }: {
  /** 「이 캐릭터로 웹툰 만들기」 — 이름과 그림을 들고 만들기 화면으로 간다. */
  onUse: (c: Character) => void;
}) {
  const [got, setGot] = useState<CharacterList | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [making, setMaking] = useState(false);

  const load = useCallback(() => {
    listCharacters()
      .then((v) => { setGot(v); setFailed(null); })
      .catch((e: Error) => setFailed(e.message));
  }, []);

  useEffect(() => { load(); }, [load]);

  /* 그리는 중인 것이 있으면 다 될 때까지 물어본다. 만들기는 곧바로 돌아오고
     그림은 뒤에서 그려진다(1분쯤) — 안 물어보면 「그리는 중」에서 멈춰 있다. */
  const drawing = got?.characters.some((c) => c.status === "drawing") ?? false;
  useEffect(() => {
    if (!drawing) return;
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [drawing, load]);

  const drop = async (c: Character) => {
    if (!window.confirm(`「${c.name}」을(를) 지울까요?`)) return;
    try {
      await removeCharacter(c.id);
      load();
    } catch (e) {
      setFailed((e as Error).message);
    }
  };

  const mine = got?.characters.filter((c) => c.mine) ?? [];
  const builtin = got?.characters.filter((c) => c.builtin) ?? [];

  return (
    <section className="chars">
      <header className="chars-head">
        <div>
          <p className="eyebrow">내 캐릭터</p>
          <h2>누구로 웹툰을 만들까요?</h2>
          {/* 값을 **먼저** 말한다. 만들고 나서 "크레딧이 모자랍니다" 를 만나면
              그때는 이미 이름과 설명을 다 적은 뒤다. */}
          {got && (
            <p className="chars-sub">
              {got.free_left > 0
                ? `오늘 ${got.free_left}개까지 무료로 만들 수 있어요`
                : `한 개에 ${got.credit_cost}크레딧`}
            </p>
          )}
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setMaking(true)}>
          + 캐릭터 만들기
        </button>
      </header>

      {failed && <p className="chars-error" role="alert">{failed}</p>}
      {!got && !failed && <p className="chars-empty">불러오는 중…</p>}

      {got && mine.length === 0 && (
        /* 만든 것이 없을 때. **없다고만 말하지 않는다** — 무엇을 하면 되는지
           같이 준다. 자캐 그림이 없어도 만들 수 있다는 것까지. */
        <div className="chars-blank">
          <p><b>아직 만든 캐릭터가 없어요.</b></p>
          <p>사진이 있으면 사진으로, 없으면 설명만으로도 만들 수 있어요.</p>
          <button type="button" className="btn btn-primary" onClick={() => setMaking(true)}>
            나만의 캐릭터 만들기
          </button>
        </div>
      )}

      {mine.length > 0 && <CharGrid list={mine} onUse={onUse} onDrop={drop} />}

      {builtin.length > 0 && (
        <>
          <h3 className="chars-section">둘러보기용 캐릭터</h3>
          <p className="chars-sub">만들 것이 없을 때 바로 써 볼 수 있어요.</p>
          <CharGrid list={builtin} onUse={onUse} />
        </>
      )}

      {making && (
        <CharacterMake
          onClose={() => setMaking(false)}
          /* 만들자마자 위자드로 넘기지 않는다 — 그림이 아직 없다. 목록에서
             「그리는 중」으로 보이다가 다 되면 그때 고른다. */
          onMade={() => { setMaking(false); load(); }}
        />
      )}
    </section>
  );
}

function CharGrid({ list, onUse, onDrop }: {
  list: Character[];
  onUse: (c: Character) => void;
  onDrop?: (c: Character) => void;
}) {
  return (
    <ul className="char-grid">
      {list.map((c) => (
        <li key={c.id} className="char-card">
          <div className="char-art" data-status={c.status}>
            {c.status === "drawing"
              ? <span className="char-art-none">그리는 중…<br />1분쯤 걸려요</span>
              : c.status === "error"
                ? <span className="char-art-none">{c.error || "못 그렸어요"}</span>
                : c.art_url
                  // eslint-disable-next-line @next/next/no-img-element
                  ? <img src={c.art_url} alt={c.name} loading="lazy" />
                  : <span className="char-art-none">그림 없음</span>}
          </div>
          <div className="char-body">
            <b className="char-name">{c.name}</b>
            {c.description && <p className="char-desc">{c.description}</p>}
            <div className="char-acts">
              {/* 아직 안 그려졌으면 못 누른다 — 그림 없이 넘어가면 위자드가
                  빈 캐릭터로 시작한다. */}
              <button type="button" className="btn btn-primary btn-sm"
                      disabled={c.status !== "ready"} onClick={() => onUse(c)}>
                {c.status === "drawing" ? "그리는 중…" : "이 캐릭터로 웹툰 만들기"}
              </button>
              {onDrop && (
                <button type="button" className="btn btn-quiet btn-sm" onClick={() => onDrop(c)}>
                  지우기
                </button>
              )}
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}
