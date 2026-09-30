import { useEffect, useLayoutEffect, useRef, useState, type FormEvent } from "react";
import Icon from "./Icon";

type Props = {
  chapter: number | null;
  maxChapter: number | null;
  failed: boolean;
  onChapter: (chapter: number) => void;
};

/** 입력 중인 숫자는 적용할 때까지 카드 목록과 회차별 초안에 반영하지 않는다. */
export default function ChapterPicker({ chapter, maxChapter, failed, onChapter }: Props) {
  const ready = chapter !== null && maxChapter !== null;
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState("");
  const [error, setError] = useState("");
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const input = useRef<HTMLInputElement>(null);

  // 보관함 등 다른 경로에서 회차가 바뀌면 이전 회차의 입력창을 닫는다.
  useEffect(() => { setOpen(false); }, [chapter, maxChapter]);

  useLayoutEffect(() => {
    if (open) {
      input.current?.focus();
      input.current?.select();
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener("pointerdown", onOutside);
    return () => document.removeEventListener("pointerdown", onOutside);
  }, [open]);

  function close() {
    setOpen(false);
    trigger.current?.focus();
  }

  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    const text = value.trim();
    const next = Number(text);
    if (!/^\d+$/.test(text) || !Number.isSafeInteger(next) || next < 1 || next > maxChapter) {
      setError(`1~${maxChapter}화 사이의 숫자를 입력해 주세요.`);
      input.current?.focus();
      return;
    }
    onChapter(next);
    close();
  }

  return (
    <div
      className="chapter-picker"
      ref={root}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}
      onKeyDown={(event) => {
        if (open && event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          close();
        }
      }}
    >
      <button
        type="button"
        className="chapter-control"
        data-part="chapter-trigger"
        ref={trigger}
        disabled={!ready}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? "piece-maker-chapter-dialog" : undefined}
        onClick={() => {
          if (open) { close(); return; }
          setValue(String(chapter));
          setError("");
          setOpen(true);
        }}
      >
        <Icon name="book" />
        <span>읽은 회차</span>
        <strong data-part="chapter-current">{ready ? `${chapter}화까지` : failed ? "장부 없음" : "불러오는 중"}</strong>
        <Icon name="down" width={14} />
      </button>
      {open && ready ? (
        <div className="chapter-popover" id="piece-maker-chapter-dialog" role="dialog" aria-labelledby="piece-maker-chapter-label">
          <form onSubmit={apply} noValidate>
            <label id="piece-maker-chapter-label" htmlFor="piece-maker-chapter-input">읽은 회차</label>
            <div className="chapter-entry">
              <div className="chapter-input-wrap">
                <input
                  id="piece-maker-chapter-input"
                  ref={input}
                  type="text"
                  inputMode="numeric"
                  enterKeyHint="done"
                  autoComplete="off"
                  spellCheck={false}
                  value={value}
                  aria-describedby={`piece-maker-chapter-range${error ? " piece-maker-chapter-error" : ""}`}
                  aria-invalid={error ? true : undefined}
                  onChange={(event) => { setValue(event.target.value); setError(""); }}
                />
                <span>화</span>
              </div>
              <button type="submit" className="btn primary">적용</button>
            </div>
            <p id="piece-maker-chapter-range" className="small muted">1~{maxChapter}화까지 선택 가능</p>
            {error ? <p id="piece-maker-chapter-error" className="chapter-error" role="alert">{error}</p> : null}
          </form>
        </div>
      ) : null}
    </div>
  );
}
