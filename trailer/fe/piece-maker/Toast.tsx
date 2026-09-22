/* 알림 — 화면 아래에 잠깐 뜨는 한 줄. 4.2초 뒤에 사라진다. */
import { useCallback, useEffect, useRef, useState } from "react";

const SHOW_MS = 4200;

export function useToast(): { message: string | null; showToast: (message: string) => void } {
  const [message, setMessage] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const showToast = useCallback((next: string) => {
    if (timer.current) clearTimeout(timer.current);
    setMessage(next);
    timer.current = setTimeout(() => setMessage(null), SHOW_MS);
  }, []);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  return { message, showToast };
}

export default function Toast({ message }: { message: string | null }) {
  return (
    <div className="toast" role="status" hidden={message === null} data-part="toast">
      <span>{message}</span>
    </div>
  );
}
