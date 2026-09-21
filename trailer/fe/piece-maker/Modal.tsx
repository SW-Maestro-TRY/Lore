/* 모달의 틀 — 브라우저의 `<dialog>` 를 그대로 쓴다.
 *
 * lore 의 다른 모달은 `createPortal` 과 `role="dialog"` 로 만든다(AuthModal). 여기서 `<dialog>` 를
 * 쓰는 까닭: 원본이 `<dialog>` 와 `showModal()` 을 썼고, 포커스 가두기와 Esc 를 브라우저가 맡아
 * 동작이 원본과 같아진다.
 *
 * `showModal()` 은 닫혀 있을 때만 부른다. 열린 `<dialog>` 에 다시 부르면 오류가 난다. */
import { useEffect, useRef, type ReactNode } from "react";
import Icon from "./Icon";

export type ModalContent = { title: string; body: ReactNode; footer?: ReactNode };

type Props = {
  /** null 이면 닫힌 것이다. */
  content: ModalContent | null;
  onClose: () => void;
};

export default function Modal({ content, onClose }: Props) {
  const dialog = useRef<HTMLDialogElement>(null);
  const open = content !== null;

  useEffect(() => {
    const el = dialog.current;
    if (!el) return;
    if (open && !el.open) el.showModal();
    if (!open && el.open) el.close();
  }, [open]);

  return (
    <dialog
      ref={dialog}
      aria-labelledby="trailer-modal-title"
      data-part="modal"
      onCancel={(event) => {
        // Esc. 브라우저가 닫게 두지 않고 화면의 상태부터 고친다.
        event.preventDefault();
        onClose();
      }}
      onClose={onClose}
    >
      {content ? (
        <div>
          <header className="dialog-head">
            <h2 id="trailer-modal-title">{content.title}</h2>
            <button className="icon-btn" data-action="close" aria-label="닫기" onClick={onClose}>
              <Icon name="close" />
            </button>
          </header>
          <div className="dialog-body">{content.body}</div>
          {content.footer ? <footer className="dialog-foot">{content.footer}</footer> : null}
        </div>
      ) : null}
    </dialog>
  );
}
