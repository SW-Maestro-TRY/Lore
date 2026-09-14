"use client";

import { useRef, useState } from "react";
import { createCharacter, type Character } from "../../lib/charApi";
import { MAX_PHOTOS, STYLE_INFO } from "../../lib/wizardData";
import { STYLE_THUMB } from "../../lib/styleThumbs";

/* 캐릭터 하나 만들기 — **화면 하나를 통째로 쓴다.**
 *
 * 처음에는 작은 창(모달)으로 만들었는데, 적을 것이 넷이고 그림체는 여덟 개를
 * 눈으로 견줘야 해서 그 안에서는 아무것도 제대로 안 보였다. 웹툰 만들기가
 * 화면을 통째로 쓰는 것과 같은 이유다.
 *
 * **두 갈래가 나란히 있다.** 사진으로 만들거나, 설명만으로 만들거나.
 * 뒤쪽이 이 기능이 생긴 이유다 — 자캐 그림이 없는 사람도 캐릭터를 가질 수
 * 있어야 한다.
 *
 * **이름도 필수가 아니다.** 이름부터 물으면 "뭐라고 부르지" 에서 멈춘다.
 * 안 적으면 서버가 지어 준다. */
export default function CharacterMake({ onClose, onMade }: {
  onClose: () => void;
  onMade: (c: Character) => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [photos, setPhotos] = useState<string[]>([]);
  const [style, setStyle] = useState(STYLE_INFO[0][0]);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const file = useRef<HTMLInputElement>(null);

  /** 여러 장을 한꺼번에 골라도 된다 — 같은 사람의 다른 각도·표정. */
  const pick = (files: FileList | null) => {
    const list = [...(files ?? [])];
    if (!list.length) return;
    const room = MAX_PHOTOS - photos.length;
    if (room <= 0) { setFailed(`사진은 ${MAX_PHOTOS}장까지 올릴 수 있습니다`); return; }
    setFailed(null);
    list.slice(0, room).forEach((f) => {
      if (f.size > 6 * 1024 * 1024) { setFailed("사진이 너무 큽니다 (6MB 까지)"); return; }
      const r = new FileReader();
      r.onload = () => setPhotos((prev) => [...prev, String(r.result)]);
      r.readAsDataURL(f);
    });
    if (file.current) file.current.value = "";
  };

  const dropPhoto = (i: number) => {
    setPhotos((prev) => prev.filter((_, idx) => idx !== i));
  };

  /** 사진이든 설명이든 **하나만** 있으면 만든다. */
  const ready = Boolean(photos.length || description.trim());

  const submit = async () => {
    if (!ready || busy) return;
    setBusy(true);
    setFailed(null);
    try {
      onMade(await createCharacter({
        name: name.trim(),
        description: description.trim(),
        photos_data: photos.length ? photos : undefined,
        style,
      }));
    } catch (e) {
      setFailed((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <section className="charmake">
      <header className="charmake-head">
        <button type="button" className="btn btn-quiet btn-sm"
                onClick={onClose} disabled={busy}>← 내 캐릭터</button>
        <h2>어떤 캐릭터인가요?</h2>
        <p className="chars-lede">
          사진이 있으면 사진으로, 없으면 설명만으로도 그립니다.
        </p>
      </header>

      <div className="charmake-body">
        <div className="charmake-card">
          <label className="char-field">
            <span>어떤 캐릭터인가요? <b className="char-req">필수</b></span>
            <textarea
              value={description}
              rows={6}
              placeholder={"성격·하는 일·생김새 등 아는 만큼.\n예) 편의점 야간 알바를 하는 구미호. 꼬리를 코트 안에 숨기고 다닌다."}
              onChange={(e) => setDescription(e.target.value)}
              disabled={busy}
            />
          </label>

          <label className="char-field">
            <span>이름 <em className="char-opt">선택 · 비우면 루가 지어요</em></span>
            <input type="text" value={name} placeholder="예: 마루"
                   onChange={(e) => setName(e.target.value)} disabled={busy} />
          </label>

          <div className="char-field">
            <span>사진 <em className="char-opt">선택 · {photos.length}/{MAX_PHOTOS}장</em></span>
            <div className="char-photo">
              {photos.length === 0
                ? <span className="char-photo-none">없어도 됩니다 — 설명만으로 그려요</span>
                : (
                  <ul className="photo-strip">
                    {photos.map((src, i) => (
                      // eslint-disable-next-line @next/next/no-img-element
                      <li className="shot" key={i}>
                        <img src={src} alt={`${i + 1}번째 사진`} />
                        <button type="button" className="shot-x" aria-label="지우기"
                                disabled={busy} onClick={() => dropPhoto(i)}>✕</button>
                      </li>
                    ))}
                  </ul>
                )}
              <input ref={file} type="file" accept="image/*" multiple hidden disabled={busy}
                     onChange={(e) => pick(e.target.files)} />
              {photos.length < MAX_PHOTOS && (
                <div className="char-photo-acts">
                  <button type="button" className="btn btn-quiet btn-sm" disabled={busy}
                          onClick={() => file.current?.click()}>
                    {photos.length ? "여러 각도로 더 올리기" : "사진 올리기"}
                  </button>
                </div>
              )}
            </div>
            <p className="char-note">
              올린 사진은 <b>생김새를 옮겨 적는 데만</b> 쓰고, 그림이 나오면 서버에서
              지웁니다. 남는 것은 그려진 캐릭터뿐이에요. 여러 각도·표정으로 올리면
              더 닮게 그려요.
            </p>
          </div>
        </div>

        <div className="charmake-card">
          {/* **그림체는 보고 고른다.** 이름만으로는 "세미리얼" 이 무엇인지 알
              수가 없다. 위자드가 쓰는 것과 같은 견본이다. */}
          <div className="char-field"><span>그림체</span></div>
          <ul className="style-pick">
            {STYLE_INFO.map(([key, label, desc]) => (
              <li key={key}>
                <button
                  type="button"
                  className={`style-card${style === key ? " is-on" : ""}`}
                  onClick={() => setStyle(key)}
                  disabled={busy}
                  aria-pressed={style === key}
                >
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={STYLE_THUMB[key]} alt="" loading="lazy" />
                  <b>{label}</b>
                  <span>{desc}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>

        <footer className="charmake-foot">
        {failed && <p className="chars-error" role="alert">{failed}</p>}
        <button type="button" className="btn btn-primary charmake-go"
                onClick={submit} disabled={!ready || busy}>
          {busy ? "보내는 중…" : "캐릭터 만들기"}
        </button>
        <p className="char-note">
          {ready
            ? "그리는 데 1분쯤 걸려요. 기다리지 않아도 목록에서 볼 수 있어요."
            : "어떤 캐릭터인지 한 줄만 적어 주세요 — 사진은 없어도 됩니다."}
        </p>
        </footer>
      </div>
    </section>
  );
}
