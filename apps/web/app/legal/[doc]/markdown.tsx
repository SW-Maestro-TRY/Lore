import type { ReactNode } from 'react';

/**
 * 약관·처리방침 전용의 아주 작은 마크다운 그리기.
 *
 * ★ 왜 라이브러리를 안 쓰는가 — 이 두 문서 말고는 마크다운을 그리는 화면이 없다.
 *   팀 전체 의존성을 하나 늘리는 것보다, 쓰는 문법만 받는 80줄이 가볍다.
 *   받는 문법은 이 두 문서가 실제로 쓰는 것뿐이다:
 *   `#`~`###` 제목 · 문단 · `-` 목록 · `1.` 목록 · `>` 인용 · `|` 표 · `---` 구분선 ·
 *   `**굵게**` · `` `코드` `` · 줄 안의 링크는 안 쓴다.
 *
 *   못 알아보는 줄은 버리지 않고 문단으로 그린다 — 법 문서에서 글이 조용히
 *   사라지는 것이 가장 나쁘다.
 */

/** `**굵게**` 와 `` `코드` `` 만 처리한다. */
function inline(text: string, key: string): ReactNode[] {
  const out: ReactNode[] = [];
  // 굵게와 코드를 한 번에 가른다.
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  parts.forEach((p, i) => {
    if (!p) return;
    if (p.startsWith('**') && p.endsWith('**')) out.push(<strong key={`${key}-${i}`}>{p.slice(2, -2)}</strong>);
    else if (p.startsWith('`') && p.endsWith('`')) out.push(<code key={`${key}-${i}`}>{p.slice(1, -1)}</code>);
    else out.push(p);
  });
  return out;
}

const isTableRow = (l: string) => l.trim().startsWith('|') && l.trim().endsWith('|');
const cells = (l: string) => l.trim().slice(1, -1).split('|').map(c => c.trim());
/** `|---|:--:|` 같은 구분 줄 */
const isTableRule = (l: string) => isTableRow(l) && cells(l).every(c => /^:?-{2,}:?$/.test(c));

const ITEM = /^(\s*)([-*]|\d+\.)\s+(.*)$/;
const indentOf = (l: string) => (ITEM.exec(l)?.[1] ?? '').length;

/** 한 덩어리의 목록 줄을 들여쓰기에 따라 중첩해 그린다. */
function List({ lines, idKey }: { lines: string[]; idKey: string }) {
  const base = Math.min(...lines.filter(l => ITEM.test(l)).map(indentOf));
  const ordered = /\d/.test(ITEM.exec(lines.find(l => indentOf(l) === base && ITEM.test(l)) as string)![2]);

  type Item = { text: string; child: string[] };
  const items: Item[] = [];
  for (const l of lines) {
    const m = ITEM.exec(l);
    if (m && m[1].length === base) items.push({ text: m[3], child: [] });
    else if (items.length) items[items.length - 1].child.push(l);
  }

  const body = items.map((it, n) => {
    const sub = it.child.filter(c => ITEM.test(c));
    // 목록이 아닌 들여쓴 줄은 그 항목 글에 이어 붙인다(원문에서 줄만 바꾼 경우).
    const tail = it.child.filter(c => !ITEM.test(c)).map(c => c.trim()).join(' ');
    return (
      <li key={n}>
        {inline(tail ? `${it.text} ${tail}` : it.text, `${idKey}-${n}`)}
        {sub.length > 0 && <List lines={it.child} idKey={`${idKey}-${n}s`} />}
      </li>
    );
  });

  return ordered ? <ol>{body}</ol> : <ul>{body}</ul>;
}

export default function Markdown({ text }: { text: string }) {
  const lines = text.split('\n');
  const out: ReactNode[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    const t = line.trim();

    if (!t) { i += 1; continue; }

    // 구분선
    if (/^-{3,}$/.test(t)) { out.push(<hr key={i} />); i += 1; continue; }

    // 제목
    const h = /^(#{1,4})\s+(.*)$/.exec(t);
    if (h) {
      const level = h[1].length;
      const Tag = (['h1', 'h2', 'h3', 'h4'] as const)[level - 1];
      out.push(<Tag key={i}>{inline(h[2], String(i))}</Tag>);
      i += 1;
      continue;
    }

    // 표 — 머리줄 + 구분줄 + 본문줄
    if (isTableRow(line) && i + 1 < lines.length && isTableRule(lines[i + 1])) {
      const head = cells(line);
      const body: string[][] = [];
      let j = i + 2;
      while (j < lines.length && isTableRow(lines[j])) { body.push(cells(lines[j])); j += 1; }
      out.push(
        <div className="legal-tablewrap" key={i}>
          <table>
            <thead>
              <tr>{head.map((c, n) => <th key={n}>{inline(c, `${i}h${n}`)}</th>)}</tr>
            </thead>
            <tbody>
              {body.map((row, r) => (
                <tr key={r}>
                  {row.map((c, n) => (
                    // 표 안에서만 <br> 을 쓴다(국외 이전 고지처럼 한 칸에 여러 줄이 필요할 때).
                    <td key={n}>
                      {c.split('<br>').map((seg, k) => (
                        <span key={k}>{k > 0 && <br />}{inline(seg, `${i}b${r}${n}${k}`)}</span>
                      ))}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>,
      );
      i = j;
      continue;
    }

    // 인용
    if (t.startsWith('>')) {
      const buf: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith('>')) {
        buf.push(lines[i].trim().replace(/^>\s?/, ''));
        i += 1;
      }
      out.push(
        <blockquote key={`q${i}`}>
          {buf.filter(Boolean).map((b, n) => <p key={n}>{inline(b, `q${i}-${n}`)}</p>)}
        </blockquote>,
      );
      continue;
    }

    // 목록 — 번호와 글머리표를 같이 받는다.
    //
    // 들여쓴 하위 목록은 **그 항목 안에** 넣는다. 전에는 하위 목록이 목록을 끊어서,
    // 제5조처럼 중간에 글머리표가 끼면 그 뒤 번호가 3이 아니라 다시 1부터 시작했다.
    // 조문은 번호가 곧 인용 근거라 틀리면 안 된다.
    if (ITEM.test(line)) {
      const block: string[] = [];
      while (i < lines.length && (ITEM.test(lines[i]) || (block.length > 0 && lines[i].trim() && /^\s/.test(lines[i])))) {
        block.push(lines[i]);
        i += 1;
      }
      out.push(<List key={`li${i}`} lines={block} idKey={`li${i}`} />);
      continue;
    }

    // 나머지는 문단. 빈 줄이 나올 때까지 이어 붙인다.
    const buf: string[] = [];
    const startKey = i;
    while (i < lines.length && lines[i].trim() && !/^(#{1,4}\s|>|\s*([-*]|\d+\.)\s|\|)/.test(lines[i]) && !/^-{3,}$/.test(lines[i].trim())) {
      buf.push(lines[i].trim());
      i += 1;
    }
    if (buf.length) out.push(<p key={startKey}>{inline(buf.join(' '), `p${startKey}`)}</p>);
    else i += 1;
  }

  return <>{out}</>;
}
