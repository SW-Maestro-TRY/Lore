"use client";

/* 웹툰 탭에는 **보조 헤더가 없다.** Lore 앱의 공용 헤더(@common/SiteHeader)
 * 하나뿐이다 — 둘이면 헷갈린다(2026-09-19 확정). 화면 사이 이동은 본문의
 * 단추·빵부스러기·「처음으로」로만 잇는다.
 *
 * 여기 남은 것은 폰의 얇은 줄(이전 · 제목 · 걸음)과 PC 의 빵부스러기 줄이다. */
import Link from "next/link";
import type { ReactNode } from "react";
import { IconBack } from "./Icons";

/** 폰의 얇은 줄 — 이전 · 제목 · 오른쪽(걸음 표시 등). PC 에서는 안 보인다. */
export function MobileTop({ back, title, right }: { back?: { href: string; label?: string; onClick?: () => void }; title: string; right?: ReactNode }) {
  return (
    <div className="mtop">
      {back ? (
        back.onClick ? (
          <button type="button" className="btn-ghost" style={{ fontSize: 13 }} onClick={back.onClick}>
            <IconBack size={16} /> {back.label ?? "이전"}
          </button>
        ) : (
          <Link href={back.href} className="btn-ghost" style={{ fontSize: 13 }}>
            <IconBack size={16} /> {back.label ?? "이전"}
          </Link>
        )
      ) : <span />}
      <b>{title}</b>
      <span className="dim" style={{ fontSize: 12, minWidth: 40, textAlign: "right" }}>{right}</span>
    </div>
  );
}

/** PC 의 빵부스러기 — 「캐릭터 › 이야기 › 완성」 한 줄. 진한 것이 지금 자리. */
export function Crumb({ items, at }: { items: string[]; at: number }) {
  return (
    <div className="crumb" aria-label="지금 위치">
      {items.map((it, i) => (
        <span key={it} style={{ display: "contents" }}>
          {i > 0 && <i>›</i>}
          {i === at ? <b>{it}</b> : <span>{it}</span>}
        </span>
      ))}
    </div>
  );
}
