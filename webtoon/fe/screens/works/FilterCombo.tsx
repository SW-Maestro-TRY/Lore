"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useT } from "../../lib/i18n";
import { IconChevronDown, IconClose } from "../../ui/Icons";

/* 둘러보기의 그림체 · 장르 칸 — 누르면 아래로 목록이 펼쳐지고, 적으면 목록이 좁혀진다.
 *
 * 만들기 화면의 세계관 칸(WorldCombo)과 모양은 같지만 여기서는 **고르기만** 된다.
 * 목록에 없는 글자는 값이 되지 않고, 고르지 않고 칸을 떠나면 적던 글자는 사라지고
 * 고른 값(없으면 비움)으로 돌아간다. 칸 오른쪽 숫자는 그 갈래의 작품 수다. */

export interface ComboOption {
  key: string;
  label: string;   // 이미 번역된 글
  count: number;
}

export default function FilterCombo({ id, name, all, options, value, onChange }: {
  id: string;
  /** 칸 이름(번역된 글) — 목록의 접근성 이름이 된다. */
  name: string;
  /** 안 골랐을 때 칸에 보이는 글(번역된 글) — 「그림체 전체」. */
  all: string;
  options: ComboOption[];
  /** 고른 key. 안 골랐으면 빈 문자열. */
  value: string;
  onChange: (key: string) => void;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [at, setAt] = useState(-1);
  const box = useRef<HTMLDivElement>(null);

  const picked = options.find((o) => o.key === value);

  const list = useMemo(() => {
    const q = text.trim().toLowerCase();
    if (!q) return options;
    return options.filter((o) => o.label.toLowerCase().includes(q) || o.key.toLowerCase().includes(q));
  }, [options, text]);

  const close = () => { setOpen(false); setText(""); };

  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) close();
    };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open]);

  useEffect(() => { setAt(-1); }, [text, open]);

  const choose = (key: string) => { onChange(key); close(); };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") { close(); return; }
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!open) { setOpen(true); return; }
      if (!list.length) return;
      const step = e.key === "ArrowDown" ? 1 : -1;
      setAt((i) => (i + step + list.length) % list.length);
      return;
    }
    if (e.key === "Enter" && open) {
      e.preventDefault();
      /* 짚은 줄이 없어도 좁혀진 것이 하나뿐이면 그것을 고른다 — 적고 바로 Enter. */
      const one = at >= 0 ? list[at] : list.length === 1 ? list[0] : undefined;
      if (one) choose(one.key);
    }
  };

  return (
    <div className={`wt-works-combo${value ? " has" : ""}`} ref={box}>
      <div className="wt-works-combo-box">
        <input
          className="field"
          role="combobox"
          aria-label={name}
          aria-expanded={open}
          aria-controls={`${id}-list`}
          aria-autocomplete="list"
          aria-activedescendant={open && at >= 0 && list[at] ? `${id}-${at}` : undefined}
          value={open ? text : picked?.label ?? ""}
          placeholder={open && picked ? picked.label : all}
          onChange={(e) => { setText(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onClick={() => setOpen(true)}
          onKeyDown={onKey}
        />
        {value ? (
          <button type="button" className="icon-btn wt-works-combo-x" aria-label={t("고른 것 풀기")}
                  onClick={() => choose("")}>
            <IconClose size={15} />
          </button>
        ) : (
          <button type="button" className="icon-btn wt-works-combo-x" aria-label={t("목록 열기")}
                  onClick={() => (open ? close() : setOpen(true))}>
            <IconChevronDown size={16} />
          </button>
        )}
      </div>

      {open && (
        <ul className="wt-works-combo-list" id={`${id}-list`} role="listbox" aria-label={name}>
          {list.length === 0 ? (
            <li className="wt-works-combo-none">{t("맞는 것이 없어요")}</li>
          ) : list.map((o, i) => (
            <li key={o.key}>
              <button type="button" id={`${id}-${i}`} role="option" aria-selected={o.key === value}
                      className={`wt-works-combo-one${i === at ? " at" : ""}${o.key === value ? " on" : ""}`}
                      onMouseEnter={() => setAt(i)}
                      onClick={() => choose(o.key === value ? "" : o.key)}>
                <span>{o.label}</span>
                <span className="wt-works-combo-n">{o.count}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
