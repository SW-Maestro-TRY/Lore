import { notFound } from 'next/navigation';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

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
 * ⚠️ 지금은 마크다운을 그대로 보여준다. 조문 형식이라 줄바꿈만 살면 읽히고,
 *    마크다운 렌더러를 넣으려면 의존성이 늘어 팀 전체에 영향이 간다(초안 단계에는 과하다).
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
    <main style={{ maxWidth: '52rem', margin: '0 auto', padding: '2.5rem 1.25rem 5rem' }}>
      <h1 style={{ fontSize: '1.5rem', marginBottom: '1.5rem' }}>{entry.title}</h1>
      {body ? (
        <pre
          style={{
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            fontFamily: 'inherit',
            lineHeight: 1.75,
            fontSize: '0.9375rem',
            color: 'var(--fg, inherit)',
          }}
        >
          {body}
        </pre>
      ) : (
        <p style={{ lineHeight: 1.75, fontSize: '0.9375rem', color: 'var(--fg, inherit)' }}>
          {entry.title}을 준비하고 있습니다. 준비되는 대로 이 화면에 올리겠습니다.
        </p>
      )}
    </main>
  );
}
