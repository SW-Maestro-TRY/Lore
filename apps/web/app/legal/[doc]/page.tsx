import { notFound } from 'next/navigation';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

/**
 * 약관·개인정보처리방침 화면.
 *
 * ★ 문서를 프론트로 복사하지 않고 `zzal/docs/` 의 원본을 빌드 시점에 읽는다.
 *   복사본을 두면 화면에 뜬 글과 서버가 기록하는 동의 판 번호가 조용히 어긋나는데,
 *   동의는 "무엇에 동의했는가" 가 증거라 어긋나면 안 된다.
 *
 * ⚠️ 지금은 마크다운을 그대로 보여준다. 조문 형식이라 줄바꿈만 살면 읽히고,
 *    마크다운 렌더러를 넣으려면 의존성이 늘어 팀 전체에 영향이 간다(초안 단계에는 과하다).
 */
const DOCS: Record<string, { title: string; file: string }> = {
  terms: { title: '이용약관', file: '이용약관-v1-초안.md' },
  privacy: { title: '개인정보처리방침', file: '개인정보처리방침-v1-초안.md' },
};

export function generateStaticParams() {
  return Object.keys(DOCS).map(doc => ({ doc }));
}

export default async function LegalPage({ params }: { params: Promise<{ doc: string }> }) {
  const { doc } = await params;
  const entry = DOCS[doc];
  if (!entry) notFound();

  // 레포 루트에서 zzal/docs 를 찾는다. next 는 apps/web 에서 도므로 두 단계 올라간다.
  const file = path.join(process.cwd(), '..', '..', 'zzal', 'docs', entry.file);
  let body: string;
  try {
    body = await readFile(file, 'utf-8');
  } catch {
    // 문서가 아직 없을 수 있다. 빈 화면보다 사실을 말하는 편이 낫다.
    body = `${entry.title} 문서를 준비 중입니다.`;
  }

  return (
    <main style={{ maxWidth: '52rem', margin: '0 auto', padding: '2.5rem 1.25rem 5rem' }}>
      <h1 style={{ fontSize: '1.5rem', marginBottom: '1.5rem' }}>{entry.title}</h1>
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
    </main>
  );
}
