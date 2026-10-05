import { useEffect, useMemo, useRef, useState } from "react";
import type { Draft } from "../lib/draft";
import type { JudgeResult } from "../lib/api";
import { shareCaption, shareChannelHref, shareContent, SOCIAL_CHANNELS } from "../lib/sharing";
import { trackResultAction } from "../lib/track";
import { COPY_FIELD_ID } from "./modals";
import Icon from "./Icon";

type Props = { draft: Draft; result: JudgeResult; onClose: () => void };
type FacebookState = "copying" | "ready" | "failed" | null;

// Brand SVG paths: https://github.com/simple-icons/simple-icons (CC0-1.0).
const CHANNEL_ICON_PATHS = {
  facebook: "M9.101 23.691v-7.98H6.627v-3.667h2.474v-1.58c0-4.085 1.848-5.978 5.858-5.978.401 0 .955.042 1.468.103a8.68 8.68 0 0 1 1.141.195v3.325a8.623 8.623 0 0 0-.653-.036 26.805 26.805 0 0 0-.733-.009c-.707 0-1.259.096-1.675.309a1.686 1.686 0 0 0-.679.622c-.258.42-.374.995-.374 1.752v1.297h3.919l-.386 2.103-.287 1.564h-3.246v8.245C19.396 23.238 24 18.179 24 12.044c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.628 3.874 10.35 9.101 11.647Z",
  threads: "M18.263 11.097c-.03-3.486-1.92-5.586-5.111-5.586-2.13 0-3.922.963-4.863 2.499l2.062 1.438c.535-.843 1.272-1.543 2.628-1.543 1.528 0 2.318.85 2.544 2.431a15 15 0 0 0-2.236-.173c-4.125 0-6.068 1.867-6.068 4.336s1.943 3.99 4.804 3.99c3.139 0 5.013-2.115 5.781-4.735.798.361 1.348 1.204 1.348 2.47 0 3.387-3.907 5.232-7.22 5.232-4.885 0-8.077-3.207-8.077-8.424 0-6.392 4.223-10.487 9.9-10.487 3.808 0 5.69 1.671 6.97 3.914l2.108-1.475C21.44 2.078 18.331 0 13.663 0 6.227 0 1.168 5.277 1.168 12.934c0 7 4.953 11.066 10.856 11.066 4.878 0 9.809-2.846 9.809-7.716 0-2.545-1.46-4.231-3.569-5.187m-6.33 4.855c-1.077 0-2.026-.512-2.026-1.453 0-1.483 1.822-1.934 3.606-1.934.678 0 1.34.045 1.927.173-.422 1.927-1.671 3.215-3.508 3.214Z",
  x: "M14.234 10.162 22.977 0h-2.072l-7.591 8.824L7.251 0H.258l9.168 13.343L.258 24H2.33l8.016-9.318L16.749 24h6.993zm-2.837 3.299-.929-1.329L3.076 1.56h3.182l5.965 8.532.929 1.329 7.754 11.09h-3.182z",
} as const;

function ChannelIcon({ id }: { id: keyof typeof CHANNEL_ICON_PATHS }) {
  return <svg className="share-channel-mark" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d={CHANNEL_ICON_PATHS[id]} /></svg>;
}

export default function SharePanel({ draft, result, onClose }: Props) {
  const content = useMemo(() => shareContent(draft, result), [draft, result]);
  const [caption, setCaption] = useState(() => shareCaption(content));
  const [notice, setNotice] = useState("");
  const [copied, setCopied] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [canShare, setCanShare] = useState(false);
  const [facebook, setFacebook] = useState<FacebookState>(null);
  const field = useRef<HTMLTextAreaElement>(null);
  const facebookDialog = useRef<HTMLDialogElement>(null);
  const manualCopy = useRef<HTMLTextAreaElement>(null);
  const copyAttempt = useRef(0);
  const facebookAttempt = useRef(0);
  const locked = useRef(false);

  useEffect(() => {
    const element = field.current;
    if (!element) return;
    let active = true;
    // 글 상자 안에서 마지막 줄이 잘리지 않도록 공유창 전체를 스크롤한다.
    const resize = () => {
      if (!active) return;
      element.style.height = "auto";
      element.style.height = `${element.scrollHeight + element.offsetHeight - element.clientHeight}px`;
    };
    let width = element.clientWidth;
    resize();
    const observer = new ResizeObserver(() => {
      if (element.clientWidth === width) return;
      width = element.clientWidth;
      resize();
    });
    observer.observe(element);
    // 글꼴이 늦게 적용되면 폭이 같아도 줄 수가 달라질 수 있다.
    void document.fonts.ready.then(resize);
    document.fonts.addEventListener("loadingdone", resize);
    return () => {
      active = false;
      observer.disconnect();
      document.fonts.removeEventListener("loadingdone", resize);
    };
  }, [caption]);

  useEffect(() => {
    try { setCanShare(typeof navigator.share === "function" && navigator.canShare?.({ text: caption }) === true); }
    catch { setCanShare(false); }
  }, [caption]);

  useEffect(() => {
    const dialog = facebookDialog.current;
    if (!dialog) return;
    if (facebook !== null && !dialog.open) dialog.showModal();
    if (facebook === null && dialog.open) dialog.close();
    if (facebook === "failed") { manualCopy.current?.focus(); manualCopy.current?.select(); }
  }, [facebook]);

  async function copy() {
    const attempt = ++copyAttempt.current;
    try {
      await navigator.clipboard.writeText(caption);
      if (attempt !== copyAttempt.current) return;
      setCopied(true); setNotice("글을 복사했어요. SNS에서 붙여넣을 수 있어요."); trackResultAction("share_done", "copy");
    } catch {
      if (attempt !== copyAttempt.current) return;
      setCopied(false); field.current?.focus(); field.current?.select(); setNotice("선택된 글을 직접 복사해 주세요.");
    }
  }

  function closeFacebook() {
    facebookAttempt.current += 1;
    setFacebook(null);
  }

  async function prepareFacebook() {
    const attempt = ++facebookAttempt.current;
    setFacebook("copying");
    try {
      await navigator.clipboard.writeText(caption);
      if (attempt !== facebookAttempt.current) return;
      setCopied(true); setFacebook("ready");
      trackResultAction("share_done", "copy");
    } catch {
      if (attempt !== facebookAttempt.current) return;
      setCopied(false); setFacebook("failed");
    }
  }

  async function nativeShare() {
    if (!caption.trim() || locked.current) return;
    locked.current = true; setSharing(true); setNotice("");
    try {
      await navigator.share({ title: content.title, text: caption });
      setNotice("공유 창에 전달했어요. 게시 여부는 선택한 앱에서 확인해 주세요.");
      trackResultAction("share_done", "native");
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError")) setNotice("공유 창을 열지 못했어요. 글을 복사해 직접 붙여넣어 주세요.");
    } finally { locked.current = false; setSharing(false); }
  }

  return <div className="share-panel" data-part="share-panel">
    <p className="share-intro">공유할 글을 확인하고 수정하세요.</p>
    <div className="share-layout share-controls">
      <section className="share-box" aria-labelledby="share-caption-title">
        <h3 id="share-caption-title"><span className="share-step">1</span><label htmlFor={COPY_FIELD_ID}>올릴 글</label></h3>
        <textarea ref={field} id={COPY_FIELD_ID} className="field share-caption" aria-label="올릴 글" value={caption}
          onChange={event => { copyAttempt.current += 1; setCaption(event.target.value); setCopied(false); setNotice(""); }} />
        <div className="share-text-tools"><span className="muted">{[...caption].length.toLocaleString()}자</span>
          <button className="btn primary" data-action="copy" onClick={() => void copy()} disabled={!caption.trim()}><Icon name="copy" width={16} />{copied ? "복사됨" : "글 복사"}</button></div>
        <p className="share-hint">SNS의 글자 수 제한에 따라 수정이 필요할 수 있어요.</p>
      </section>
      <section className="share-box share-destinations" aria-labelledby="share-sns-title">
        <h3 id="share-sns-title"><span className="share-step">2</span>내 계정에 게시</h3>
        <p className="share-channel-help">Facebook은 글 복사·게시 안내를, Threads·X는 글 작성 화면을 열어요.</p>
        <div className="share-channels">{SOCIAL_CHANNELS.map(channel => channel.id === "facebook" ?
          <button key={channel.id} className="share-channel" data-channel={channel.id} disabled={!caption.trim()}
            aria-haspopup="dialog" aria-label="Facebook 글 복사 및 게시 안내" onClick={() => void prepareFacebook()}>
            <ChannelIcon id={channel.id} /><span>{channel.name}</span>
          </button> : <a key={channel.id} className="share-channel"
            data-channel={channel.id} href={shareChannelHref(channel, caption)} target="_blank" rel="noopener noreferrer"
            aria-label={`${channel.name} 글 작성하기, 글 자동 입력 (새 탭 또는 앱)`}
            onClick={() => trackResultAction("share_done", channel.id)}>
            <ChannelIcon id={channel.id} /><span>{channel.name}</span>
          </a>)}</div>
        {canShare && <>
          <button className="btn share-full" data-action="native-share" disabled={sharing || !caption.trim()} onClick={() => void nativeShare()}>기기 공유로 보내기</button>
          <p className="share-hint">앱에 따라 글이 전달되지 않을 수 있어요. 게시 전에 글을 확인해 주세요.</p>
        </>}
      </section>
    </div>
    <p className="share-notice" role="status">{notice}</p>
    <div className="share-bottom"><button className="btn" onClick={onClose}>판정으로 돌아가기</button></div>
    <dialog ref={facebookDialog} className="share-facebook-dialog" data-part="facebook-notice" aria-labelledby="facebook-notice-title"
      onCancel={event => { event.preventDefault(); event.stopPropagation(); closeFacebook(); }}
      onClose={event => { event.stopPropagation(); closeFacebook(); }}>
      {facebook !== null && <>
        <header className="dialog-head">
          <h2 id="facebook-notice-title">Facebook에 직접 게시해 주세요</h2>
          <button className="icon-btn" aria-label="Facebook 안내 닫기" onClick={closeFacebook}><Icon name="close" /></button>
        </header>
        <div className="dialog-body">
          <p role="status">{facebook === "copying" ? "글을 복사하고 있어요." : facebook === "ready" ? "글을 복사했어요." : "자동 복사를 하지 못했어요. 아래 글을 직접 복사해 주세요."}</p>
          {facebook === "failed" && <textarea ref={manualCopy} className="field share-manual-copy" aria-label="직접 복사할 글" readOnly value={caption} />}
          <p className="share-facebook-help">Facebook에서 글쓰기를 열고, 복사한 글을 붙여넣은 뒤 직접 게시해 주세요.</p>
          <p className="share-hint">로그인이 필요할 수 있어요.</p>
        </div>
        <footer className="dialog-foot">
          <button className="btn" onClick={closeFacebook}>돌아가기</button>
          {facebook === "failed" && <button className="btn" data-action="facebook-copy-retry" onClick={() => void prepareFacebook()}>다시 복사</button>}
          {facebook !== "copying" && <a className="btn primary" data-action="facebook-continue" href={SOCIAL_CHANNELS[0].href} target="_blank" rel="noopener noreferrer"
            onClick={() => trackResultAction("share_done", "facebook")}>Facebook으로 이동</a>}
        </footer>
      </>}
    </dialog>
  </div>;
}
