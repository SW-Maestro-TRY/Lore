import { notFound } from 'next/navigation';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import Markdown from './markdown';
import './legal.css';

/**
 * 약관·개인정보처리방침 화면.
 *
 * ★ 문서를 프론트로 복사하지 않고 `apps/web/content/legal/` 의 원본을 빌드 시점에 읽는다.
 *   복사본을 두면 화면에 뜬 글과 서버가 기록하는 동의 판 번호가 조용히 어긋나는데,
 *   동의는 "무엇에 동의했는가" 가 증거라 어긋나면 안 된다.
 *
 * ★ `content/legal/` 에 있는 것은 **게시본뿐이다.** 작성 과정의 메모(법률 검토 전이라는
 *   경고, 아직 정하지 못한 정책, 구현이 안 된 항목 같은 것)는 `webtoon/docs/legal/` 의
 *   작업본에만 있고 여기로 넘어오지 않는다. 전에는 작업본을 통째로 읽어서
 *   "그대로 서비스에 게시해서는 안 됩니다" 라는 줄이 사용자 화면에 그대로 떴다.
 *
 * ★ 아직 안 채운 칸(`[[...]]`)이 하나라도 남아 있으면 **문서를 내보내지 않는다.**
 *   사업자명·연락처·보존기간이 빈 약관은 법적으로 효력을 기대하기 어렵고,
 *   빈칸을 사용자에게 보여 주는 것보다 "준비 중" 이 솔직하다.
 *
 * ★ 마크다운은 같은 폴더의 markdown.tsx 가 그린다. 라이브러리를 안 쓴 이유는 거기
 *   맨 위에 적어 두었다 — 요약하면, 이 두 문서 말고 마크다운을 그리는 화면이 없어서
 *   팀 전체 의존성을 늘리는 것보다 쓰는 문법만 받는 편이 가볍기 때문이다.
 *   전에는 마크다운을 <pre> 에 그대로 쏟아서, 본문에 `## 제1조` 와 `|---|---|` 가
 *   그대로 보였다. 표가 있는 문서라 그대로는 못 읽는다.
 */
const DOCS: Record<string, { title: string; file: string }> = {
  terms: { title: '이용약관', file: '이용약관.md' },
  privacy: { title: '개인정보처리방침', file: '개인정보처리방침.md' },
};

/** 아직 안 채운 칸. 하나라도 있으면 게시하지 않는다. */
const BLANK = /\[\[[^\]]+\]\]/;

export function generateStaticParams() {
  return Object.keys(DOCS).map(doc => ({ doc }));
}

export default async function LegalPage({ params }: { params: Promise<{ doc: string }> }) {
  const { doc } = await params;
  const entry = DOCS[doc];
  if (!entry) notFound();

  const file = path.join(process.cwd(), 'content', 'legal', entry.file);
  let body: string | null = null;
  try {
    const text = await readFile(file, 'utf-8');
    // 빈칸이 남은 문서는 게시본이 아니다. 빌드 로그에 남겨 두어야 아무도 모르고 지나치지 않는다.
    if (BLANK.test(text)) {
      console.warn(
        `[legal] ${entry.file} 에 아직 안 채운 칸이 남아 있어 게시하지 않는다: ` +
          (text.match(/\[\[[^\]]+\]\]/g) ?? []).join(', '),
      );
    } else {
      body = text;
    }
  } catch {
    // 파일이 아직 없을 수 있다. 빈 화면보다 준비 중이라고 말하는 편이 낫다.
  }

  return (
    <main className="legal">
      {body ? (
        <Markdown text={body} />
      ) : (
        <>
          <h1>{entry.title}</h1>
          <p>{entry.title}을 준비하고 있습니다. 준비되는 대로 이 화면에 올리겠습니다.</p>
        </>
      )}
    </main>
  );
}
