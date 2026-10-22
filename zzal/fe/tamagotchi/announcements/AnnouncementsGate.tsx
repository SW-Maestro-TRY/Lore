'use client';

import { createContext, useContext, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { useAuth } from '@common/auth/useAuth';
import { useZzalLocale } from '../../lib/locale';
import { ANNOUNCEMENTS, announcementDismissalKey, type Announcement } from './announcements';
import './announcements.css';

const WORDS = {
  ko: { title: '공지사항', close: '닫기', hide: '이 공지 다시 보지 않기', empty: '아직 등록된 공지가 없어요.', back: '공지 목록', storage: '이 브라우저에 저장하지 못했어요. 다음 접속 때 다시 표시될 수 있어요.' },
  en: { title: 'Announcements', close: 'Close', hide: 'Don’t show this announcement again', empty: 'No announcements yet.', back: 'All announcements', storage: 'Could not save in this browser. This announcement may appear on your next visit.' },
  ja: { title: 'お知らせ', close: '閉じる', hide: 'このお知らせを今後表示しない', empty: 'お知らせはまだありません。', back: 'お知らせ一覧', storage: 'このブラウザに保存できませんでした。次回も表示される場合があります。' },
};
const OpenAnnouncements = createContext<((returnTo?: HTMLElement | null) => void) | null>(null);
export const useOpenAnnouncements = () => useContext(OpenAnnouncements);

/** Holds the first screen before mounting its progression owners. Later manual reads keep them mounted. */
export default function AnnouncementsGate({ children, announcements = ANNOUNCEMENTS }: {
  children: ReactNode; announcements?: readonly Announcement[];
}) {
  const auth = useAuth();
  const scope = auth.user ? `user-${auth.user.userId}` : 'guest';
  const returnTo = useRef<HTMLElement | null>(null);
  const checkedScopes = useRef(new Set<string>());
  const mounted = useRef(announcements.length === 0);
  const [ready, setReady] = useState(announcements.length === 0);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<Announcement | null>(null);
  const [startup, setStartup] = useState(false);
  useLayoutEffect(() => {
    if (announcements.length && auth.isLoading) return;
    if (checkedScopes.current.has(scope)) return;
    checkedScopes.current.add(scope);
    const latest = announcements[0];
    let hidden = false;
    try { hidden = !!latest && localStorage.getItem(announcementDismissalKey(latest.id, scope)) === '1'; } catch { /* Storage denial must never block entry. */ }
    if (latest && !hidden) { setSelected(latest); setStartup(!mounted.current); setOpen(true); }
    mounted.current = true; setReady(true);
  }, [announcements, auth.isLoading, scope]);
  const close = () => { setOpen(false); setStartup(false); };
  return <OpenAnnouncements.Provider value={target => { returnTo.current = target ?? null; setSelected(null); setOpen(true); }}>
    {ready && !startup && children}
    {open && <AnnouncementDialog key={scope} scope={scope} returnTo={returnTo.current} announcements={announcements} selected={selected} onSelect={setSelected} onClose={close} startup={startup} />}
  </OpenAnnouncements.Provider>;
}

function AnnouncementDialog({ announcements, selected, onSelect, onClose, startup, scope, returnTo }: {
  announcements: readonly Announcement[]; selected: Announcement | null; onSelect: (entry: Announcement | null) => void; onClose: () => void; startup: boolean; scope: string; returnTo: HTMLElement | null;
}) {
  const locale = useZzalLocale(), words = WORDS[locale];
  const dialog = useRef<HTMLDialogElement>(null), closeButton = useRef<HTMLButtonElement>(null);
  const [hidden, setHidden] = useState(false), [storageFailed, setStorageFailed] = useState(false);
  useEffect(() => {
    const node = dialog.current, previous = returnTo ?? document.activeElement;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    node?.showModal(); closeButton.current?.focus({ preventScroll: true });
    return () => {
      node?.close(); document.body.style.overflow = overflow;
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus({ preventScroll: true });
    };
  }, []);
  useLayoutEffect(() => {
    setStorageFailed(false);
    closeButton.current?.focus({ preventScroll: true });
    try { setHidden(!!selected && localStorage.getItem(announcementDismissalKey(selected.id, scope)) === '1'); }
    catch { setHidden(false); }
  }, [selected, scope]);
  const saveHidden = (checked: boolean) => {
    setHidden(checked); setStorageFailed(false);
    if (!selected) return;
    try {
      if (checked) localStorage.setItem(announcementDismissalKey(selected.id, scope), '1');
      else localStorage.removeItem(announcementDismissalKey(selected.id, scope));
    } catch { setStorageFailed(true); }
  };
  const content = selected?.content[locale];
  return <dialog ref={dialog} className="zzal-announcements" aria-labelledby="zzal-announcements-title" data-announcements onKeyDown={event => {
    event.stopPropagation();
    if (event.key !== 'Tab') return;
    const controls = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),[tabindex="0"]')];
    const first = controls[0], last = controls[controls.length - 1];
    if (event.shiftKey && (document.activeElement === first || !event.currentTarget.contains(document.activeElement))) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }} onCancel={event => { event.preventDefault(); onClose(); }}>
    <header className="za-heading"><h2 id="zzal-announcements-title">{words.title}</h2><button ref={closeButton} type="button" onClick={onClose}>{words.close}</button></header>
    <div className="za-content">
      {content && selected ? <article key={selected.id}>
        {!startup && <button className="za-back" type="button" onClick={() => onSelect(null)}>{words.back}</button>}
        <h3>{content.title}</h3><time dateTime={selected.publishedAt}>{selected.publishedAt.slice(0, 10)}</time>
        {content.paragraphs.map((paragraph, index) => <p key={index}>{paragraph}</p>)}
      </article> : announcements.length ? <ul>{announcements.map(entry => <li key={entry.id}><button type="button" onClick={() => onSelect(entry)}><span>{entry.content[locale].title}</span><time dateTime={entry.publishedAt}>{entry.publishedAt.slice(0, 10)}</time></button></li>)}</ul> : <p className="za-empty">{words.empty}</p>}
    </div>
    {selected && <footer><label className="za-hide"><input type="checkbox" checked={hidden} onChange={event => saveHidden(event.target.checked)} /><span>{words.hide}</span></label>{storageFailed && <p role="status">{words.storage}</p>}</footer>}
  </dialog>;
}
