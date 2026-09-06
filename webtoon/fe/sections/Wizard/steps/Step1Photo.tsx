"use client";

import { useRef } from "react";
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
    form.photos.length === 0
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
        {/* **캐릭터를 골라 왔으면 사진을 또 안 받는다.** 그림은 서버가 그
            캐릭터 것으로 붙인다. 여기서 사진을 또 올리라고 하면 캐릭터를
            만들어 둔 의미가 없어진다. */}
        {form.characterId ? (
          <div className="wiz-picked">
            {form.characterArt && (
              // eslint-disable-next-line @next/next/no-img-element
              <img className="wiz-picked-art" src={form.characterArt} alt={form.name} />
            )}
            <div className="wiz-picked-body">
              <p className="wiz-picked-tag">이 캐릭터로 만들어요</p>
              <b>{form.name}</b>
            </div>
            {/* 바꾸는 길은 **조용히** 둔다. 이미 고르고 온 사람에게 크게 보일
                단추가 아니다 — 알약처럼 붙여 뒀더니 이름 옆의 딱지처럼 읽혔다. */}
            <button type="button" className="wiz-picked-swap"
                    onClick={() => onChange({
                      characterId: undefined, characterArt: undefined,
                      name: "", character: "",
                    })}>
              바꾸기
            </button>
          </div>
        ) : (
        <div className="photo-row">
          {/* **두 갈래를 나란히 둔다.**
           *
           * 전에는 사진 칸 하나만 크게 있고, 그 아래에 "자캐 사진이 없으신가요?"
           * 를 작은 글씨로 붙여 뒀다. 사진이 없는 사람은 그 큰 칸 앞에서 이미
           * 막힌 뒤라 아래를 안 읽는다.
           *
           * 이제 같은 크기로 둘을 나란히 놓는다 — 올리거나, 캐릭터에서
           * 고르거나. 둘 다 정상적인 길이라는 것을 자리로 말한다. */}
          {/* eslint-disable-next-line jsx-a11y/label-has-associated-control */}
          <label className="photo-drop" onClick={() => inputRef.current?.click()}>
            <input
              ref={inputRef}
              type="file"
              accept="image/png,image/jpeg,image/webp"
              multiple
              hidden
              onChange={(e) => addFiles(e.target.files)}
            />
            <span className="photo-hint">
              캐릭터 사진 <em className="req">필수</em>
            </span>
            <div className="photo-strip">
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
          </label>

          <button type="button" className="photo-drop photo-pick" onClick={onPickCharacter}>
            <span className="photo-pick-icon" aria-hidden="true">✦</span>
            <span className="photo-hint">내 캐릭터에서 고르기</span>
            <span className="photo-count">
              만들어 둔 캐릭터를 쓰거나, 사진 없이 새로 만들어요
            </span>
          </button>

          <ul className="photo-rules">
            <li>본인이 찍었거나 직접 그린 사진, 또는 쓸 권한이 있는 사진만 올려주세요.</li>
            <li>실존 인물은 본인이거나 동의를 받은 경우에만 올려주세요.</li>
            <li>올린 사진은 캐릭터를 만드는 데만 쓰고, 시트가 나오면 서버에서 지웁니다.</li>
            <li>
              완성한 웹툰은 <b>둘러보기</b>에 공개됩니다. 마이페이지에서 언제든 비공개로 바꿀 수 있어요.
            </li>
          </ul>
        </div>
        )}

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
    </section>
  );
}
