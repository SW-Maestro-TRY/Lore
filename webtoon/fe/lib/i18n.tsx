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
 * 고른 언어는 localStorage `lore_lang` 에 남는다. 처음 고르는 순서는 주소의
 * `/ko`·`/en`·`/ja` (apps/web/middleware.ts 가 이 자리를 벗겨 내도 주소창·
 * `location.pathname` 에는 그대로 남는다) → 저장된 값 → 브라우저 언어다. 주소에
 * 언어가 박혀 있으면 그 값을 저장도 해서, 다음에 언어 없는 주소로 옮겨도 유지된다. */
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
  if (!registry.includes(dict)) {
    registry.push(dict);
    patterns = null;
  }
}

const KEY = "lore_lang";

/** 주소 맨 앞 자리가 언어 코드면 그 값. rewrite 뒤에도 브라우저 주소창은 그대로라 통한다. */
function urlLang(): Lang | null {
  if (typeof window === "undefined") return null;
  const seg = window.location.pathname.split("/")[1];
  return LANGS.some((l) => l.key === seg) ? (seg as Lang) : null;
}

function initialLang(): Lang {
  if (typeof window === "undefined") return "ko";
  const fromUrl = urlLang();
  if (fromUrl) return fromUrl;
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

/* 서버가 숫자를 끼워 보낸 문구(「3번째 사진을 읽지 못했습니다」)는 원문이 매번 달라
 * 사전 키와 글자가 안 맞는다. 사전 키에 `{n}` 자리가 있으면 그 자리를 아무 글자로
 * 보고 맞춰 본 뒤, 잡힌 값을 번역문의 같은 자리에 넣는다. 정확히 맞는 키가 없을
 * 때만 본다. */
type Pattern = { re: RegExp; names: string[]; entry: Dict[string] };
let patterns: Pattern[] | null = null;

function patternsOf(): Pattern[] {
  if (patterns) return patterns;
  const out: Pattern[] = [];
  for (const dict of registry) {
    for (const [src, entry] of Object.entries(dict)) {
      if (!/\{\w+\}/.test(src)) continue;
      const names: string[] = [];
      const body = src.split(/(\{\w+\})/).map((part) => {
        const m = /^\{(\w+)\}$/.exec(part);
        if (m) {
          names.push(m[1]);
          return "(.+?)";
        }
        return part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      }).join("");
      out.push({ re: new RegExp(`^${body}$`), names, entry });
    }
  }
  patterns = out;
  return out;
}

function lookup(lang: Lang, src: string): string {
  if (lang === "ko") return src;
  for (let i = registry.length - 1; i >= 0; i--) {
    const hit = registry[i][src]?.[lang];
    if (hit) return hit;
  }
  for (const p of patternsOf()) {
    const target = p.entry[lang];
    if (!target) continue;
    const m = p.re.exec(src);
    if (!m) continue;
    const vars: Record<string, string> = {};
    p.names.forEach((n, i) => { vars[n] = m[i + 1]; });
    return fill(target, vars);
  }
  return src;
}

/* 지금 화면 언어. 훅을 못 쓰는 자리(api 의 오류 문구, DOM 을 직접 그리는 코드)가
 * 읽는다. LangProvider 가 언어를 정하거나 바꿀 때마다 맞춰 둔다. */
let current: Lang = "ko";

/** 훅 밖에서 쓰는 t(). 서버가 보낸 한국어 문구를 지금 화면 언어로 옮길 때 쓴다. */
export function translateNow(src: string, vars?: Record<string, string | number>): string {
  return fill(lookup(current, src), vars);
}

/** 영문 번역. 행동 기록(track)은 한글 값을 버리므로 화면 이름을 기호로 바꿀 때 쓴다. 없으면 원문. */
export function englishOf(src: string): string {
  return lookup("en", src);
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
    const l = initialLang();
    current = l;
    setLangState(l);
    // 주소가 명시한 언어는 저장도 한다 — 다음에 언어 없는 주소로 옮겨도 유지되게.
    if (urlLang()) {
      try {
        localStorage.setItem(KEY, l);
      } catch {
        /* 못 남겨도 이번 방문은 이미 반영됐다 */
      }
    }
  }, []);
  const setLang = useCallback((l: Lang) => {
    current = l;
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
