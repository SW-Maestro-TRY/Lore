import { useEffect, useState } from "react";
import { readResult, setVisibility, shareKeyOf, type Visibility } from "../lib/api";
import { copyLink, shareUrl, withShareUtm } from "../lib/share";
import { registerDict, useT, type Dict } from "../lib/i18n";
import { track } from "../lib/track";
import "./VisibilityPicker.css";

/* 공개 범위 고르개(#898) — 마이페이지 · 둘러보기(내 작품) 카드가 같이 쓴다.
 * 예전 공개 스위치 자리에 들어간다. 링크 공개일 때만 옆에 「링크 복사」가 붙는다. */
const dict: Dict = {
  "공개": { en: "Public", ja: "公開", zh: "公开" },
  "링크 공유(일부공개)": { en: "Link sharing (unlisted)", ja: "リンク共有（限定公開）", zh: "链接分享（部分公开）" },
  "비공개": { en: "Private", ja: "非公開", zh: "不公开" },
  "공개 범위": { en: "Visibility", ja: "公開範囲", zh: "公开范围" },
  "관리자 비공개": { en: "Hidden by admin", ja: "管理者が非公開", zh: "管理员设为不公开" },
  "링크 복사": { en: "Copy link", ja: "リンクをコピー", zh: "复制链接" },
  "링크를 복사했어요": { en: "Link copied", ja: "リンクをコピーしました", zh: "已复制链接" },
  "복사하지 못했어요": { en: "Couldn't copy", ja: "コピーできませんでした", zh: "复制失败" },
  "바꾸지 못했어요": { en: "Couldn't change it", ja: "変更できませんでした", zh: "无法更改" },
};
registerDict(dict);

const OPTIONS: { value: Visibility; label: string }[] = [
  { value: "public", label: "공개" },
  { value: "link", label: "링크 공유(일부공개)" },
  { value: "private", label: "비공개" },
];

export default function VisibilityPicker({
  runId,
  initial,
  locked = false,
  className = "",
  where,
}: {
  runId: string;
  initial: Visibility;
  /** 관리자가 비공개 처리한 작품(#638) — 고를 수 없다. */
  locked?: boolean;
  className?: string;
  /** 기록에 남길 자리(mypage · works). */
  where: string;
}) {
  const t = useT();
  const [value, setValue] = useState<Visibility>(initial);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  /* 안내 한 줄은 잠깐만 — 공유 메뉴(ShareMenu)와 같은 길이. */
  useEffect(() => {
    if (!note) return;
    const id = window.setTimeout(() => setNote(""), 2400);
    return () => window.clearTimeout(id);
  }, [note]);

  /* 서버에 먼저 보내고, 실패하면 되돌린다 — 화면만 바뀌어 있으면 다음에 들어왔을 때 값이 달라 보인다. */
  const change = async (want: Visibility) => {
    if (want === value) return;
    const was = value;
    track("visibility_change", { run: runId, result: want, where });
    setValue(want);
    setBusy(true);
    setNote("");
    try {
      const out = await setVisibility(runId, want);
      setValue(out.visibility ?? (out.public ? "public" : want));
    } catch (e) {
      setValue(was);
      setNote((e as Error).message || t("바꾸지 못했어요"));
    } finally {
      setBusy(false);
    }
  };

  /* 열쇠는 바꾼 직후면 기억에 있고, 처음 들어온 카드면 결과를 한 번 읽어 받는다(주인에게만 온다). */
  const copy = async () => {
    setNote("");
    let key = shareKeyOf(runId);
    if (!key) {
      try { key = (await readResult(runId)).share_key; } catch { key = undefined; }
    }
    const ok = !!key && await copyLink(withShareUtm(shareUrl(runId, 1, key), "copy", "webtoon"));
    track("share_click", { run: runId, ep: 1, target: "copy", where });
    setNote(ok ? t("링크를 복사했어요") : t("복사하지 못했어요"));
  };

  if (locked) {
    return <span className={`wt-vis ${className}`}>{t("관리자 비공개")}</span>;
  }

  return (
    <span className={`wt-vis ${className}`}>
      <select className="wt-vis-select" value={value} disabled={busy} aria-label={t("공개 범위")}
              onChange={(e) => void change(e.target.value as Visibility)}>
        {OPTIONS.map((o) => <option key={o.value} value={o.value}>{t(o.label)}</option>)}
      </select>
      {value === "link" && (
        <button type="button" className="wt-vis-copy" disabled={busy} onClick={() => void copy()}>{t("링크 복사")}</button>
      )}
      {note && <span className="wt-vis-note" role="status">{note}</span>}
    </span>
  );
}
