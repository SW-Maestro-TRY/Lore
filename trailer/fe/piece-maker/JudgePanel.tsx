/* 판정 자리 — 맡기기 단추, 상태 한 줄, 판정 결과. 작성 패널의 "03" 아래에 놓인다.
 * 맡긴 뒤에는 단추 대신 "새 가설 쓰기"와, 판정을 기다리는 동안 "지금 확인"이 놓인다 — 맡긴 가설은 고칠 수 없다(NA decisions.md 1-23).
 * 결과(`result`)는 가설의 판정 칸에서 온다. 근거 단추의 제목은 부르는 쪽이 인용 카드 · 담은 카드 · 목록에서 찾아 준다. */
import type { JudgeResult, PresentationSection } from "../lib/api";
import { GRADES, JUDGE_TEXT, readableJudgement } from "../lib/judgement";
import Icon from "./Icon";

type Props = {
  /** 단추를 누를 수 있는가. 카드를 받았고, 담은 카드와 주장이 있고, 맡기는 중이 아니어야 한다. */
  canJudge: boolean;
  /** 맡기는 중인가. */
  waiting: boolean;
  /** 이 초안을 이미 맡겼는가. 단추 대신 안내와 "새 가설 쓰기"를 보인다. */
  frozen: boolean;
  /** 맡긴 판정을 아직 기다리는가. "지금 확인" 단추를 보인다. */
  pending: boolean;
  /** 단추 아래의 한 줄. */
  stateText: string;
  /** 판정 1회에 깎는 크레딧. 장부 정보를 받기 전에는 null. */
  price: number | null;
  /** 내 크레딧. 로그인이 없거나 아직 못 읽었으면 null. */
  balance: number | null;
  /** 받아 둔 판정. 없으면 결과 상자를 숨긴다. */
  result: JudgeResult | null;
  chapter: number | null;
  /** 근거 단추에 붙일 카드 제목. */
  titleOf: (id: string) => string | undefined;
  onJudge: () => void;
  /** "새 가설 쓰기". 맡긴 초안을 비우고 새로 시작한다. */
  onNew: () => void;
  /** "지금 확인". 판정을 지금 되묻는다. */
  onRefresh: () => void;
  onOpen: (id: string) => void;
};

function Sections({ sections }: { sections: PresentationSection[] }) {
  return (
    <>
      {sections.map((section, index) => (
        <section className="judge-section" data-part="judge-section" key={index}>
          <h4>{section.title}</h4>
          <p>{section.text}</p>
        </section>
      ))}
    </>
  );
}

function ReferenceLinks({ ids, label, titleOf, onOpen }: { ids: string[]; label: string } & Pick<Props, "titleOf" | "onOpen">) {
  return (
    <>
      <strong className="small">{label}</strong>
      <div className="judge-links">
        {ids.length ? (
          ids.map((id) => (
            <button className="btn judge-link" data-action="open" data-card-id={id} key={id} onClick={() => onOpen(id)}>
              {id} · {titleOf(id) || id}
            </button>
          ))
        ) : (
          <span className="small muted">확인된 근거 없음</span>
        )}
      </div>
    </>
  );
}

/** 판정 결과. 편집본을 보여 줄 수 있으면 편집본을 위에 두고 원문을 접어 둔다. 아니면 원문을 보인다. */
function JudgeResultView({ result, chapter, titleOf, onOpen }: { result: JudgeResult } & Pick<Props, "chapter" | "titleOf" | "onOpen">) {
  const value = result.judgement;
  const { presentation, notice } = readableJudgement(result);
  return (
    <>
      <div className="row between">
        <strong data-part="judge-grade" data-grade={value.grade}>{GRADES[value.grade]}</strong>
        <span className="tag">{chapter}화 기록 기준</span>
      </div>
      {presentation ? (
        <div className="judge-edited" data-part="judge-edited">
          <p className="small muted judge-edition">문맥 편집본</p>
          <h3 className="judge-headline">{presentation.headline}</h3>
          <Sections sections={presentation.sections} />
          {presentation.details.length ? (
            <details className="judge-detail" data-part="judge-details">
              <summary>추가 검토</summary>
              <Sections sections={presentation.details} />
            </details>
          ) : null}
        </div>
      ) : (
        <>
          {notice ? (
            <p className="small muted judge-editor-notice" data-part="judge-notice">
              {notice}
            </p>
          ) : null}
          <p className="judge-reason" data-part="judge-reason">
            {value.reason}
          </p>
        </>
      )}
      <ReferenceLinks ids={value.support} label="뒷받침하는 근거" titleOf={titleOf} onOpen={onOpen} />
      <ReferenceLinks ids={value.against} label="반박하는 근거" titleOf={titleOf} onOpen={onOpen} />
      {presentation ? (
        <details className="judge-detail judge-original">
          <summary>편집 전 원문</summary>
          <p className="small muted">편집본을 위에 둔 채 원문과 비교할 수 있습니다.</p>
          <p className="judge-reason">{value.reason}</p>
        </details>
      ) : null}
      <p className="small muted judge-footnote">근거를 누르면 카드의 원래 기록을 확인할 수 있습니다.</p>
    </>
  );
}

export default function JudgePanel({ canJudge, waiting, frozen, pending, stateText, price, balance, result, chapter, titleOf, onJudge, onNew, onRefresh, onOpen }: Props) {
  return (
    <>
      <p className="small muted" id="trailer-judge-help">
        앞으로의 전개에 대한 내 주장을 선택한 카드·해석과 기존 장부에 대조합니다. 가능성 있음·가능성 낮음·판정 보류로 구분하며, 실제
        확률이나 정답 판정은 아닙니다. 판정은 사람이 돌려서 시간이 걸립니다 — 맡긴 뒤에는 이 자리와 "내 가설"에서 결과를 볼 수 있습니다.
      </p>
      {price !== null ? (
        <p className="small muted" data-part="judge-credit">
          {JUDGE_TEXT.price(price)}
          {balance !== null ? ` · ${JUDGE_TEXT.balance(balance)}` : ""}
        </p>
      ) : null}
      {frozen ? (
        <div className="judge-submitted row" data-part="judge-submitted">
          <button className="preview-link predict-cta" data-action="new-draft" onClick={onNew}>
            <span>새 가설 쓰기</span>
            <Icon name="arrow" />
          </button>
          {pending ? (
            <button className="btn quiet" data-action="refresh" onClick={onRefresh}>
              지금 확인
            </button>
          ) : null}
        </div>
      ) : (
        <button
          className="preview-link predict-cta"
          data-action="judge"
          aria-describedby="trailer-judge-help"
          aria-controls="trailer-judge-result"
          disabled={!canJudge}
          onClick={onJudge}
        >
          <span>{waiting ? "맡기는 중…" : "가설 판정하기"}</span>
          <Icon name="arrow" />
        </button>
      )}
      <p className="small muted" role="status" style={{ marginTop: 8 }} data-part="judge-state">
        {stateText}
      </p>
      <div id="trailer-judge-result" className="inline-preview" aria-live="polite" hidden={result === null} data-part="judge-result">
        {result ? <JudgeResultView result={result} chapter={chapter} titleOf={titleOf} onOpen={onOpen} /> : null}
      </div>
    </>
  );
}
