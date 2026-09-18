"use client";

/* 언어 — 한국어 · English · 日本語 · 中文.
 *
 * 화면 글은 **한국어 원문이 곧 키**다: `t("어떤 캐릭터를 만들어볼까요?")`.
 * 사전(screens/<영역>/i18n.ts)이 그 원문에 다른 언어를 달아 두고, 없으면
 * 원문이 그대로 나온다 — 번역이 빠져도 화면이 비지 않는다. 키를 따로 짓지
 * 않는 이유: 캔버스 문구를 그대로 옮긴 화면이라 원문이 이미 유일하고, 새
 * 문구를 넣을 때 사전 한 줄만 더하면 된다.
 *
 * 값에 `{n}` 같은 자리가 있으면 `t("…{n}…", {n: 3})` 로 채운다.
 *
 * 서버가 만든 글(이야기 후보·카드의 반전·운명·대사)은 AI 가 한국어로 쓴
 * 것이라 여기서 번역하지 않는다 — 그건 생성 언어의 문제다.
 *
 * 고른 언어는 localStorage `lore_lang` 에 남고, 처음엔 브라우저 언어를 본다. */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

export type Lang = "ko" | "en" | "ja" | "zh";
export const LANGS: { key: Lang; label: string }[] = [
  { key: "ko", label: "한국어" },
  { key: "en", label: "English" },
  { key: "ja", label: "日本語" },
  { key: "zh", label: "中文" },
];

/** 원문 → {en, ja, zh}. 영역마다 하나씩 등록한다. */
export type Dict = Record<string, Partial<Record<Exclude<Lang, "ko">, string>>>;

const registry: Dict[] = [];

/** 영역의 사전을 올린다. 모듈이 읽힐 때 한 번 부르면 된다(중복은 무시). */
export function registerDict(dict: Dict): void {
  if (!registry.includes(dict)) registry.push(dict);
}

const KEY = "lore_lang";

function initialLang(): Lang {
  if (typeof window === "undefined") return "ko";
  try {
    const saved = localStorage.getItem(KEY) as Lang | null;
    if (saved && LANGS.some((l) => l.key === saved)) return saved;
  } catch {
    /* 저장소를 못 읽으면 브라우저 언어로 */
  }
  const nav = (navigator.language || "ko").toLowerCase();
  if (nav.startsWith("ja")) return "ja";
  if (nav.startsWith("zh")) return "zh";
  if (nav.startsWith("en")) return "en";
  return "ko";
}

function lookup(lang: Lang, src: string): string {
  if (lang === "ko") return src;
  for (let i = registry.length - 1; i >= 0; i--) {
    const hit = registry[i][src]?.[lang];
    if (hit) return hit;
  }
  return src;
}

function fill(text: string, vars?: Record<string, string | number>): string {
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
}

export type T = (src: string, vars?: Record<string, string | number>) => string;

interface Ctx {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: T;
}

const LangContext = createContext<Ctx>({ lang: "ko", setLang: () => {}, t: (s, v) => fill(s, v) });

export function LangProvider({ children }: { children: ReactNode }) {
  /* 서버에서 미리 그릴 때는 늘 한국어 — 브라우저에 와서 저장된 언어로 바꾼다.
     처음부터 브라우저 값을 읽으면 서버 HTML 과 달라 hydration 이 어긋난다. */
  const [lang, setLangState] = useState<Lang>("ko");
  useEffect(() => {
    setLangState(initialLang());
  }, []);
  const setLang = useCallback((l: Lang) => {
    setLangState(l);
    try {
      localStorage.setItem(KEY, l);
    } catch {
      /* 못 남겨도 이번 방문은 바뀐다 */
    }
  }, []);
  useEffect(() => {
    document.documentElement.lang = lang === "zh" ? "zh-CN" : lang;
  }, [lang]);
  const t = useCallback<T>((src, vars) => fill(lookup(lang, src), vars), [lang]);
  const value = useMemo(() => ({ lang, setLang, t }), [lang, setLang, t]);
  return <LangContext.Provider value={value}>{children}</LangContext.Provider>;
}

export function useT(): T {
  return useContext(LangContext).t;
}

export function useLang(): Ctx {
  return useContext(LangContext);
}

/** 언어 고르기 — 푸터·마이페이지에 두는 작은 줄. */
export function LangSwitch({ className }: { className?: string }) {
  const { lang, setLang } = useLang();
  return (
    <div className={className} role="group" aria-label="Language" style={{ display: "inline-flex", gap: 6, flexWrap: "wrap" }}>
      {LANGS.map((l) => (
        <button key={l.key} type="button" className={`tag${lang === l.key ? " on" : ""}`}
                aria-pressed={lang === l.key} onClick={() => setLang(l.key)}>
          {l.label}
        </button>
      ))}
    </div>
  );
}
