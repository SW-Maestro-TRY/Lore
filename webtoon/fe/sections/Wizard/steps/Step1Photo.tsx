"use client";

import { useRef, useState } from "react";
import { MAX_PHOTOS, type WizardForm } from "../../../lib/wizardData";

/* 1 · 수면 — 사진 · 이름 · 캐릭터 설명. haeun/landing의 setupPhoto() 를
 * React 상태(form.photos)로 옮겼다 — data URL 배열인 것은 그대로다. */
export default function Step1Photo({
  form,
  onChange,
  onPickCharacter,
}: {
  form: WizardForm;
  onChange: (patch: Partial<WizardForm>) => void;
  /** 「내 캐릭터에서 고르기」 — 캐릭터 탭으로 간다. */
  onPickCharacter: () => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);

  /* 사진 칸을 누르면 **무엇으로 채울지부터** 묻는다. 바로 파일 고르개를 열면
     자캐 그림이 없는 사람은 거기서 막힌다 — 그 사람에게도 길이 있다는 것을
     이 자리에서 말해야 한다. */
  const [choosing, setChoosing] = useState(false);

  const addFiles = (files: FileList | null) => {
    const list = [...(files ?? [])];
    if (!list.length) return;
    const room = MAX_PHOTOS - form.photos.length;
    if (room <= 0) {
      window.alert(`사진은 ${MAX_PHOTOS}장까지 올릴 수 있습니다`);
      return;
    }
    list.slice(0, room).forEach((file) => {
      if (!file.type.startsWith("image/")) return;
      if (file.size > 6 * 1024 * 1024) {
        window.alert("사진이 너무 큽니다 (6MB 까지)");
        return;
      }
      const fr = new FileReader();
      fr.onload = () => {
        onChange({ photos: [...form.photos, String(fr.result)] });
      };
      fr.readAsDataURL(file);
    });
    if (inputRef.current) inputRef.current.value = "";
  };

  const removePhoto = (i: number) => {
    onChange({ photos: form.photos.filter((_, idx) => idx !== i) });
  };

  const countLabel =
    form.characterId
      ? "내 캐릭터에서 골랐어요 · ✕ 를 눌러 되돌립니다"
      : form.photos.length === 0
      ? "눌러서 사진을 올려주세요"
      : form.photos.length === 1
        ? `1 / ${MAX_PHOTOS}장 · 각도를 바꿔 더 올리면 더 닮게 그립니다`
        : `${form.photos.length} / ${MAX_PHOTOS}장`;

  return (
    <section className="wiz-step" data-step="1">
      {/* 걸음마다 "말"(눈금·제목·안내)과 "손이 닿는 것"(입력 카드)을
          한 겹으로 묶어 둔다. 폰에서는 위아래로 그냥 흐르고, PC 에서는
          이 덩어리가 왼쪽 칸으로 간다(webtoon.css 의 .wiz-say). */}
      <div className="wiz-say">
        <p className="wiz-eyebrow">수면 · STEP 1 / 5</p>
        <h3 className="wiz-title">
          누구를 데리고
          <br />
          바다로 갈까요?
        </h3>
        <p className="wiz-sub">아는 만큼만 적으세요. 빈 칸은 루가 채웁니다.</p>
      </div>

      <div className="wiz-card">
        <div className="photo-row">
          {/* **파일 고르개는 이 칸 밖에 둔다.**
           *
           * 전에는 이 칸이 <label> 이고 그 안에 파일 입력이 있었다. 라벨은
           * 눌리면 안에 든 입력을 <b>브라우저가 자동으로</b> 연다 — 그래서
           * 무엇으로 채울지 묻는 창과 파일 창이 같이 떴다. 이제 이 칸은 묻기만
           * 하고, 파일 창은 「사진 올리기」를 고른 뒤에 연다. */}
          <input
            ref={inputRef}
            type="file"
            accept="image/png,image/jpeg,image/webp"
            multiple
            hidden
            onChange={(e) => addFiles(e.target.files)}
          />
          <div className="photo-drop" role="button" tabIndex={0}
               onClick={() => { if (!form.characterId) setChoosing(true); }}
               onKeyDown={(e) => {
                 if (e.key === "Enter" || e.key === " ") {
                   e.preventDefault();
                   if (!form.characterId) setChoosing(true);
                 }
               }}>
            <span className="photo-hint">
              {form.characterId
                ? <>캐릭터 <b>{form.name}</b></>
                : <>캐릭터 사진 <em className="req">필수</em></>}
            </span>
            <div className="photo-strip">
              {/* **골라 온 캐릭터도 사진과 같은 자리에 같은 모양으로 둔다.**
                  따로 칸을 만들었더니 크기도 모양도 이 화면과 겉돌았다. */}
              {form.characterId && form.characterArt && (
                // eslint-disable-next-line @next/next/no-img-element
                <figure className="shot">
                  <img src={form.characterArt} alt={form.name} />
                  <button
                    type="button"
                    className="shot-x"
                    aria-label="다른 캐릭터로"
                    onClick={(e) => {
                      e.preventDefault();
                      e.stopPropagation();
                      onChange({ characterId: undefined, characterArt: undefined,
                                 name: "", character: "" });
                    }}
                  >
                    ✕
                  </button>
                </figure>
              )}
              {form.photos.map((src, i) => (
                // eslint-disable-next-line @next/next/no-img-element
                <figure className="shot" key={i}>
                  <img src={src} alt={`${i + 1}번째 사진`} />
                  <button
                    type="button"
                    className="shot-x"
                    aria-label="지우기"
                    onClick={(e) => {
                      e.preventDefault();
                      e.stopPropagation();
                      removePhoto(i);
                    }}
                  >
                    ✕
                  </button>
                </figure>
              ))}
              {form.photos.length < MAX_PHOTOS && (
                <span className="photo-slot" aria-hidden="true">
                  +
                </span>
              )}
            </div>
            <span className="photo-count">{countLabel}</span>
          </div>

          <ul className="photo-rules">
            <li>본인이 찍었거나 직접 그린 사진, 또는 쓸 권한이 있는 사진만 올려주세요.</li>
            <li>실존 인물은 본인이거나 동의를 받은 경우에만 올려주세요.</li>
            <li>올린 사진은 캐릭터를 만드는 데만 쓰고, 시트가 나오면 서버에서 지웁니다.</li>
            <li>
              완성한 웹툰은 <b>둘러보기</b>에 공개됩니다. 마이페이지에서 언제든 비공개로 바꿀 수 있어요.
            </li>
          </ul>
        </div>

        <label className="field">
          <span>
            이름 <em className="req">필수</em>
          </span>
          <input
            type="text"
            maxLength={40}
            placeholder="예: 민시하"
            value={form.name}
            onChange={(e) => onChange({ name: e.target.value })}
          />
        </label>

        <label className="field">
          <span>
            캐릭터 설명 <small>선택</small>
          </span>
          <textarea
            rows={4}
            placeholder={"성격·말투·관계 등 아는 만큼.\n예) 장난기 많은데 겁은 많아서 친구 앞에서만 센 척한다"}
            value={form.character}
            onChange={(e) => onChange({ character: e.target.value })}
          />
        </label>
      </div>
      {choosing && (
        <div className="pick-way" role="dialog" aria-modal="true"
             aria-label="캐릭터를 어떻게 넣을까요">
          <button type="button" className="pick-way-veil" aria-label="닫기"
                  onClick={() => setChoosing(false)} />
          <div className="pick-way-box">
            <button type="button" className="pick-way-x" aria-label="닫기"
                    onClick={() => setChoosing(false)}>✕</button>
            <h3>캐릭터를 어떻게 넣을까요?</h3>
            <button
              type="button"
              className="pick-way-one"
              onClick={() => { setChoosing(false); inputRef.current?.click(); }}
            >
              <b>캐릭터 사진 올리기</b>
              <span>자캐 그림이나 사진이 있으면 그걸로 그려요</span>
            </button>
            <button
              type="button"
              className="pick-way-one"
              onClick={() => { setChoosing(false); onPickCharacter(); }}
            >
              <b>캐릭터 직접 만들기</b>
              <span>사진이 없어도 돼요 — 설명만으로 그려 드려요</span>
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
