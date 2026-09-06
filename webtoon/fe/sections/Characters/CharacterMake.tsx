"use client";

import { useEffect, useRef, useState } from "react";
import { createCharacter, type Character } from "../../lib/charApi";
import { STYLE_INFO } from "../../lib/wizardData";

/* 캐릭터 하나 만들기.
 *
 * **두 갈래가 나란히 있다.** 사진으로 만들거나, 설명만으로 만들거나.
 * 뒤쪽이 이 기능이 생긴 이유다 — 자캐 그림이 없는 사람도 캐릭터를 가질 수
 * 있어야 한다. 그래서 사진 쪽을 위에 두되 **선택**이라고 적고, 설명은 어느
 * 쪽이든 받는다.
 *
 * 올린 사진은 **서버에 안 남는다.** 외모를 글로 옮기는 데만 쓰고 그림이
 * 나오면 지운다 — 그 말을 여기서 해 준다. 안 적어 두면 얼굴 사진을 올리는
 * 일이 그냥 무서운 일이 된다. */
export default function CharacterMake({ onClose, onMade }: {
  onClose: () => void;
  onMade: (c: Character) => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [photo, setPhoto] = useState<string | null>(null);
  const [style, setStyle] = useState(STYLE_INFO[0][0]);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const file = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape" && !busy) onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, busy]);

  const pick = (f: File | undefined) => {
    if (!f) return;
    if (f.size > 6 * 1024 * 1024) { setFailed("사진이 너무 큽니다 (6MB 까지)"); return; }
    const r = new FileReader();
    r.onload = () => setPhoto(String(r.result));
    r.readAsDataURL(f);
  };

  const ready = name.trim() && (photo || description.trim());

  const submit = async () => {
    if (!ready || busy) return;
    setBusy(true);
    setFailed(null);
    try {
      const made = await createCharacter({
        name: name.trim(),
        description: description.trim(),
        photo_data: photo || undefined,
        style,
      });
      onMade(made);
    } catch (e) {
      setFailed((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="credit-modal" role="dialog" aria-modal="true" aria-label="캐릭터 만들기">
      <button type="button" className="credit-modal-veil" aria-label="닫기"
              onClick={() => { if (!busy) onClose(); }} />

      <div className="credit-modal-box char-make">
        <header className="credit-modal-head">
          <h3>캐릭터 만들기</h3>
          <button type="button" className="credit-modal-x" onClick={onClose}
                  disabled={busy} aria-label="닫기">✕</button>
        </header>

        <div className="credit-modal-body">
          <label className="char-field">
            <span>이름 <b className="char-req">필수</b></span>
            <input type="text" value={name} placeholder="예: 차사"
                   onChange={(e) => setName(e.target.value)} disabled={busy} />
          </label>

          <label className="char-field">
            <span>어떤 캐릭터인가요?</span>
            <textarea
              value={description}
              rows={4}
              placeholder={"성격·하는 일·생김새 등 아는 만큼.\n예) 택배만 배달하는 저승사자. 200년째 같은 일을 한다."}
              onChange={(e) => setDescription(e.target.value)}
              disabled={busy}
            />
          </label>

          <div className="char-field">
            <span>사진 <em className="char-opt">선택</em></span>
            {/* **선택이라고 적는다.** 여기가 이 기능이 생긴 자리다 — 사진이
                없으면 못 만드는 줄 알고 나가는 사람을 붙잡는 것. */}
            <div className="char-photo">
              {photo
                // eslint-disable-next-line @next/next/no-img-element
                ? <img src={photo} alt="올린 사진" />
                : <span className="char-photo-none">없어도 됩니다 — 설명만으로 그려요</span>}
              <input ref={file} type="file" accept="image/*" hidden disabled={busy}
                     onChange={(e) => pick(e.target.files?.[0])} />
              <div className="char-photo-acts">
                <button type="button" className="btn btn-quiet btn-sm" disabled={busy}
                        onClick={() => file.current?.click()}>
                  {photo ? "다른 사진" : "사진 올리기"}
                </button>
                {photo && (
                  <button type="button" className="btn btn-quiet btn-sm" disabled={busy}
                          onClick={() => setPhoto(null)}>빼기</button>
                )}
              </div>
            </div>
            <p className="char-note">
              올린 사진은 <b>생김새를 옮겨 적는 데만</b> 쓰고, 그림이 나오면 서버에서
              지웁니다. 남는 것은 그려진 캐릭터뿐이에요.
            </p>
          </div>

          <label className="char-field">
            <span>그림체</span>
            <select value={style} onChange={(e) => setStyle(e.target.value)} disabled={busy}>
              {STYLE_INFO.map(([key, label]) => (
                <option key={key} value={key}>{label}</option>
              ))}
            </select>
          </label>

          {failed && <p className="chars-error" role="alert">{failed}</p>}

          <button type="button" className="btn btn-primary char-go"
                  onClick={submit} disabled={!ready || busy}>
            {busy ? "그리는 중… (1분쯤 걸려요)" : "캐릭터 만들기"}
          </button>
          {!ready && !busy && (
            <p className="char-note">이름과, 사진 또는 설명 중 하나가 있으면 만들 수 있어요.</p>
          )}
        </div>
      </div>
    </div>
  );
}
