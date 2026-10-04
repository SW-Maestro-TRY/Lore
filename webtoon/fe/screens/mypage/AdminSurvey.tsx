"use client";

/**
 * 설문 답 모아 보기(#471) — 관리자 계정에만 보인다. 서버가 관리자가 아니면 403 을 주고,
 * 그러면 이 칸은 아무것도 그리지 않는다.
 */
import { useEffect, useState } from "react";
import { adminSurveyRows, type SurveyRow } from "../../lib/api";
import { registerDict, useT } from "../../lib/i18n";
import { answerLabel, SURVEY_SHORT } from "../../ui/Survey";

export default function AdminSurvey() {
  const t = useT();
  const [rows, setRows] = useState<SurveyRow[] | null>(null);

  useEffect(() => {
    adminSurveyRows().then(setRows).catch(() => setRows(null));
  }, []);

  if (rows === null) return null;

  return (
    <div className="card wt-my-setting wt-survey-admin">
      <b>{t("설문 답 모아 보기 (관리자)")}</b>
      <span className="muted">{t("새 것부터 {n}개", { n: rows.length })}</span>
      {rows.map((r) => (
        <div key={r.id} className="wt-survey-row">
          <header>
            <b>{r.kind === "FULL" ? t("전체 설문") : t("완성 직후")}</b>
            <span>{new Date(r.created_at).toLocaleString()}</span>
            {r.run_id && <span>run {r.run_id}</span>}
            {r.user_id && <span>user {r.user_id}</span>}
            {r.rewarded > 0 && <span>+{r.rewarded}C</span>}
          </header>
          <dl>
            {Object.keys(SURVEY_SHORT).filter((k) => (r.answers as Record<string, unknown>)[k] !== undefined).map((k) => (
              <FragmentRow key={k} k={`${k.replace("_", " ")} ${SURVEY_SHORT[k]}`}
                           v={answerLabel(k, (r.answers as Record<string, never>)[k], t)} />
            ))}
          </dl>
          {r.comment && <blockquote>{r.comment}</blockquote>}
          {r.wants_interview && <span>{t("인터뷰 가능")}: {r.contact || "—"}</span>}
        </div>
      ))}
    </div>
  );
}

function FragmentRow({ k, v }: { k: string; v: string }) {
  return (<><dt>{k}</dt><dd>{v}</dd></>);
}

registerDict({
  "설문 답 모아 보기 (관리자)": { en: "Survey answers (admin)", ja: "アンケート回答一覧（管理者）", zh: "问卷回答汇总（管理员）" },
  "새 것부터 {n}개": { en: "Newest first · {n}", ja: "新しい順 · {n}件", zh: "按最新 · {n} 条" },
  "전체 설문": { en: "Full survey", ja: "全体アンケート", zh: "完整问卷" },
  "완성 직후": { en: "After reading", ja: "読了直後", zh: "读完后" },
  "인터뷰 가능": { en: "Interview OK", ja: "インタビュー可", zh: "可访谈" },
});
