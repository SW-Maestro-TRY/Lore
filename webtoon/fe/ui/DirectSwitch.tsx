"use client";

/**
 * 「바로 만들기」 스위치(2026-10-26) — 켜면 이야기·캐릭터·장면 확인 자리에서 멈추지 않고 끝까지 만든다.
 * 끄면 세 자리에서 멈춰 보여 준다. 만들기 화면(1화)과 다음 화 화면이 같은 것을 쓴다.
 * 아래 줄의 세 칸이 지금 어디서 멈추는지(꺼짐) · 건너뛰는지(켜짐)를 보여 준다.
 */
import { registerDict, useT } from "../lib/i18n";
import "./DirectSwitch.css";

registerDict({
  "바로 만들기": { en: "Create straight through", ja: "すぐに作る", zh: "直接生成" },
  "중간에 묻지 않고 끝까지 만들어요. 완성되면 알려 드려요.": { en: "We won't stop to ask — we'll make it all the way and let you know when it's done.", ja: "途中で確認せず最後まで作ります。完成したらお知らせします。", zh: "中途不询问，一直做到完成，完成后通知你。" },
  "이야기 · 캐릭터 · 장면마다 멈춰서 보여 드려요. 확인하고 고친 뒤 그려요.": { en: "We pause at the story, characters and scenes so you can check and edit before drawing.", ja: "ストーリー・キャラクター・シーンごとに止まってお見せします。確認・修正してから描きます。", zh: "在故事、角色、场景处暂停给你看，确认修改后再绘制。" },
  "이야기 확인": { en: "Story", ja: "ストーリー確認", zh: "确认故事" },
  "캐릭터 확인": { en: "Characters", ja: "キャラクター確認", zh: "确认角色" },
  "장면 확인": { en: "Scenes", ja: "シーン確認", zh: "确认场景" },
  "완성": { en: "Done", ja: "完成", zh: "完成" },
});

const STOPS = ["이야기 확인", "캐릭터 확인", "장면 확인"];

export default function DirectSwitch({ on, onChange, disabled }: { on: boolean; onChange: (on: boolean) => void; disabled?: boolean }) {
  const t = useT();
  return (
    <button type="button" role="switch" aria-checked={on} disabled={disabled}
            className={`wt-direct${on ? " on" : ""}`} onClick={() => onChange(!on)}>
      <span className="wt-direct-head">
        <span className="wt-direct-text">
          <b>{t("바로 만들기")}</b>
          <span className="wt-direct-desc">{on
            ? t("중간에 묻지 않고 끝까지 만들어요. 완성되면 알려 드려요.")
            : t("이야기 · 캐릭터 · 장면마다 멈춰서 보여 드려요. 확인하고 고친 뒤 그려요.")}</span>
        </span>
        <span className="wt-direct-knob" aria-hidden="true"><i /></span>
      </span>
      <span className="wt-direct-steps" aria-hidden="true">
        {STOPS.map((s) => <span key={s} className="wt-direct-stop">{t(s)}</span>)}
        <span className="wt-direct-stop end">{t("완성")}</span>
      </span>
    </button>
  );
}
