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
 * 껍데기와 카드는 「둘러보기」(Works)와 같은 모양이다. 두 화면이 나란히
 * 있는데 결이 다르면 다른 서비스처럼 보인다. */
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

  /* 만들기는 **화면 하나를 통째로 쓴다.** 작은 창으로 두면 적을 것과 그림체
     여덟 개가 그 안에 안 들어간다. */
  if (making) {
    return (
      <CharacterMake
        onClose={() => setMaking(false)}
        /* 만들자마자 위자드로 넘기지 않는다 — 그림이 아직 없다. 목록에서
           「그리는 중」으로 보이다가 다 되면 그때 고른다. */
        onMade={() => { setMaking(false); load(); }}
      />
    );
  }

  const mine = got?.characters.filter((c) => c.mine) ?? [];
  const builtin = got?.characters.filter((c) => c.builtin) ?? [];

  const mineBlock = (
    <ul className="char-grid">
      {mine.map((c) => (
        <CharCard key={c.id} c={c} onUse={onUse} onDrop={drop} />
      ))}
      {/* **만드는 문을 목록 안에 둔다.** 구석에 단추 하나만 두면 빈
          화면에서 갈 곳이 안 보인다. */}
      <li>
        <button type="button" className="char-new" onClick={() => setMaking(true)}>
          <b>+</b>
          새 캐릭터
          <span>사진 없이 설명만으로도</span>
        </button>
      </li>
    </ul>
  );

  const builtinBlock = builtin.length > 0 ? (
    <div className="chars-builtin">
      <h3 className="chars-section">둘러보기용 캐릭터</h3>
      <p className="chars-lede">만들 것이 없을 때 바로 써 볼 수 있어요.</p>
      <ul className="char-grid">
        {builtin.map((c) => <CharCard key={c.id} c={c} onUse={onUse} />)}
      </ul>
    </div>
  ) : null;

  return (
    <section className="chars">
      <header className="chars-head">
        <p className="eyebrow">내 캐릭터</p>
        <h2>누구로 웹툰을 만들까요?</h2>
        <p className="chars-lede">
          캐릭터를 만들어 두면 웹툰을 만들 때마다 다시 적지 않아도 돼요.
          사진이 있으면 사진으로, 없으면 설명만으로도 만들 수 있어요.
        </p>
        {/* 값을 **먼저** 말한다. 만들고 나서 "크레딧이 모자랍니다" 를 만나면
            그때는 이미 다 적은 뒤다. */}
        {got && (
          <p className="chars-quota">
            {got.free_left > 0
              ? `오늘 ${got.free_left}개까지 무료`
              : `한 개에 ${got.credit_cost}크레딧`}
          </p>
        )}
      </header>

      {failed && <p className="chars-error" role="alert">{failed}</p>}
      {!got && !failed && <p className="chars-empty">불러오는 중…</p>}

      {/* **만들어 둔 것이 없으면 둘러보기용을 위로 올린다.**

          이 화면의 제목은 「누구로 웹툰을 만들까요?」다. 그런데 만든 것이
          없는 사람에게 맨 위가 빈 목록과 「+ 새 캐릭터」 하나면, 물어 놓고
          고를 것을 안 준 셈이 된다. 바로 쓸 수 있는 것을 먼저 보여 주고,
          만드는 문은 그 아래에 둔다 — 처음 온 사람은 대개 하나 골라서
          한 편 만들어 보는 쪽이 먼저다.

          만들어 둔 것이 있으면 예전 순서 그대로다. 그 사람에게는 자기
          캐릭터가 목적이고 둘러보기용은 보조다. */}
      {got && mine.length === 0 && builtinBlock}
      {got && mineBlock}
      {got && mine.length > 0 && builtinBlock}
    </section>
  );
}

function CharCard({ c, onUse, onDrop }: {
  c: Character;
  onUse: (c: Character) => void;
  onDrop?: (c: Character) => void;
}) {
  return (
    <li className="char-card">
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
          {/* 아직 안 그려졌으면 못 누른다 — 그림 없이 넘어가면 위자드가 빈
              캐릭터로 시작한다. */}
          <button type="button" className="btn btn-primary btn-sm"
                  disabled={c.status !== "ready"} onClick={() => onUse(c)}>
            {c.status === "drawing" ? "그리는 중…" : "이 캐릭터로 웹툰 만들기"}
          </button>
          {onDrop && c.status !== "drawing" && (
            <button type="button" className="btn btn-quiet btn-sm" onClick={() => onDrop(c)}>
              지우기
            </button>
          )}
        </div>
      </div>
    </li>
  );
}
