import { useEffect, useMemo, useRef, useState } from "react";
import type { Draft } from "../lib/draft";
import type { JudgeResult } from "../lib/api";
import { shareCaption, shareContent, SOCIAL_CHANNELS } from "../lib/sharing";
import { makeShareImage } from "../lib/shareImage";
import { COPY_FIELD_ID } from "./modals";
import Icon from "./Icon";

type Props = { draft: Draft; result: JudgeResult; onClose: () => void };
type ImageState = { status: "loading" } | { status: "failed" } | { status: "ready"; url: string; file: File; shortened: boolean; canShare: boolean };

function ChannelIcon({ id }: { id: string }) {
  if (id === "instagram") return <svg viewBox="0 0 28 28" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><rect x="3" y="3" width="22" height="22" rx="6" /><circle cx="14" cy="14" r="5" /><circle cx="21" cy="7.5" r="1" fill="currentColor" stroke="none" /></svg>;
  return <span className={`share-channel-mark ${id}`} aria-hidden="true">{id === "facebook" ? "f" : id === "threads" ? "@" : "𝕏"}</span>;
}

export default function SharePanel({ draft, result, onClose }: Props) {
  const content = useMemo(() => shareContent(draft, result), [draft, result]);
  const [caption, setCaption] = useState(() => shareCaption(content));
  const [picture, setPicture] = useState<ImageState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  const [notice, setNotice] = useState("");
  const [copied, setCopied] = useState(false);
  const [sharing, setSharing] = useState(false);
  const field = useRef<HTMLTextAreaElement>(null);
  const locked = useRef(false);
  const imageKey = JSON.stringify(content);

  useEffect(() => {
    let alive = true;
    let url: string | undefined;
    setPicture({ status: "loading" });
    void makeShareImage(content).then(({ blob, shortened }) => {
      if (!alive) return;
      url = URL.createObjectURL(blob);
      const file = new File([blob], `piece-maker-${content.chapter}화.png`, { type: "image/png" });
      let canShare = false;
      try { canShare = typeof navigator.share === "function" && navigator.canShare?.({ files: [file] }) === true; } catch { /* 파일 공유가 막혀 있으면 저장·복사를 사용한다. */ }
      setPicture({ status: "ready", url, file, shortened, canShare });
    }).catch(() => { if (alive) setPicture({ status: "failed" }); });
    return () => { alive = false; if (url) URL.revokeObjectURL(url); };
    // 부모가 판정 객체를 새로 만들어도 내용이 같으면 이미지를 다시 만들지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [imageKey, attempt]);

  async function copy() {
    try { await navigator.clipboard.writeText(caption); setCopied(true); setNotice("글을 복사했어요. SNS에서 붙여넣을 수 있어요."); }
    catch { setCopied(false); field.current?.focus(); field.current?.select(); setNotice("선택된 글을 직접 복사해 주세요."); }
  }

  async function nativeShare() {
    if (picture.status !== "ready" || locked.current) return;
    locked.current = true; setSharing(true); setNotice("");
    try {
      await navigator.share({ title: content.title, text: caption, files: [picture.file] });
      setNotice("공유 창에 전달했어요. 게시 여부는 선택한 앱에서 확인해 주세요.");
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError")) setNotice("공유 창을 열지 못했어요. 이미지를 저장하고 글을 복사해 주세요.");
    } finally { locked.current = false; setSharing(false); }
  }

  return <div className="share-panel" data-part="share-panel">
    <p className="share-intro">결과 이미지를 첨부하고, 함께 올릴 글을 붙여넣으세요.</p>
    <div className="share-layout">
      <section className="share-box share-image-box" aria-labelledby="share-image-title">
        <h3 id="share-image-title"><span className="share-step">1</span>첨부할 이미지</h3>
        {picture.status === "ready" ? <>
          <img className="share-result-image" data-part="share-image" src={picture.url} width={1080} height={1350}
            alt={`${content.chapter}화 기준 · ${content.title} · ${content.grade}. ${content.reason}`} />
          <p className="share-image-meta">PNG · 1080 × 1350</p>
          <a className="btn share-full" data-action="download-image" href={picture.url} download={picture.file.name} onClick={() => setNotice("저장한 이미지를 SNS 게시물에 첨부해 주세요.")}>이미지 저장</a>
          {picture.shortened && <p className="share-hint">긴 제목·주장·판정 이유는 이미지에서 일부 생략했습니다.</p>}
        </> : <div className="share-image-placeholder" role="status">
          {picture.status === "loading" ? "이미지를 준비하는 중…" : <><p>이미지를 만들지 못했어요.</p><button className="btn" onClick={() => setAttempt(n => n + 1)}>다시 만들기</button></>}
        </div>}
      </section>
      <div className="share-controls">
        <section className="share-box" aria-labelledby="share-caption-title">
          <h3 id="share-caption-title"><span className="share-step">2</span><label htmlFor={COPY_FIELD_ID}>함께 올릴 글</label></h3>
          <textarea ref={field} id={COPY_FIELD_ID} className="field share-caption" aria-label="함께 올릴 글" value={caption}
            onChange={event => { setCaption(event.target.value); setCopied(false); setNotice(""); }} />
          <div className="share-text-tools"><span className="muted">{[...caption].length.toLocaleString()}자 · 직접 수정할 수 있어요</span>
            <button className="btn primary" data-action="copy" onClick={() => void copy()} disabled={!caption.trim()}><Icon name="copy" width={16} />{copied ? "복사됨" : "글 복사"}</button></div>
        </section>
        <section className="share-box" aria-labelledby="share-sns-title">
          <h3 id="share-sns-title"><span className="share-step">3</span>내 계정에 게시</h3>
          <p className="share-channel-help">SNS에서 저장한 이미지를 첨부하고 복사한 글을 붙여넣으세요. 글자 수 제한은 게시 화면에서 확인해 주세요.</p>
          <div className="share-channels">{SOCIAL_CHANNELS.map(channel => <a key={channel.id} className="share-channel"
            data-channel={channel.id} href={channel.href} target="_blank" rel="noopener noreferrer" aria-label={`${channel.name} 열기`}>
            <ChannelIcon id={channel.id} /><span>{channel.name}</span>
          </a>)}</div>
          {picture.status === "ready" && picture.canShare && <>
            <button className="btn share-full" data-action="native-share" disabled={sharing} onClick={() => void nativeShare()}>이미지와 글을 앱으로 공유</button>
            <p className="share-hint">이미지와 글을 함께 받을 수 있는 앱에서 사용하세요.</p>
          </>}
        </section>
      </div>
    </div>
    <p className="share-notice" role="status">{notice}</p>
    <div className="share-bottom"><span>이미지 저장 + 글 복사 → 내 SNS에서 게시</span><button className="btn" onClick={onClose}>판정으로 돌아가기</button></div>
  </div>;
}
