// 스타일 문자열 안에서 **색 함수가 안 불린 채 글자로 남은 것**을 잡는다.
//
// ★★ 왜 이 검사가 필요한가 — 2026-09-21 에 실제로 50곳이 이렇게 들어갔다.
//   `boxShadow: '0 12px 30px ink(.2)'` 는 **문법상 멀쩡한 문자열**이라 tsc 도 e2e 도 안 잡는데,
//   브라우저는 그 값을 통째로 버린다. 그래서 **시트 뒤 어두운 막이 안 깔리고 테두리가 사라진다** —
//   화면이 "조금 밋밋해질" 뿐 아무 데서도 빨간불이 안 들어온다. 사람 눈으로만 잡히는 종류라
//   기계가 대신 본다.
//
// ★ 함수 목록을 여기 적지 않는다 — `yeoul/ui.ts` 에서 **읽어 온다**. 새 색 함수가 생겨도
//   자동으로 검사 범위에 들어온다(목록을 두 곳에 두면 언젠가 갈리고, 갈린 쪽은 조용하다).
//
// 잡는 것 = 홑/겹따옴표 문자열 안의 `함수이름(`.  안 잡는 것 = 템플릿 리터럴(`${ink(.2)}`)·주석.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { expect, test } from '@playwright/test';

const FE = join(__dirname, '../../../zzal/fe');
const UI = join(FE, 'tamagotchi/yeoul/ui.ts');

/** `ui.ts` 가 내보내는 **함수**들의 이름. 색 함수가 늘어도 여기서 같이 늘어난다. */
function tokenFunctionNames(): string[] {
  const src = readFileSync(UI, 'utf8');
  return [...src.matchAll(/export const ([a-zA-Z0-9_]+) = \(/g)].map((m) => m[1]);
}

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) sourceFiles(p, out);
    else if (/\.tsx?$/.test(p)) out.push(p);
  }
  return out;
}

/**
 * 홑/겹따옴표 문자열을 뽑는다. 템플릿 리터럴의 **글자 부분**은 건너뛴다 — 거긴 `${}` 로 진짜 값이 들어간다.
 * 주석도 건너뛴다(주석에 예시로 적어 둔 것까지 잡으면 설명을 못 쓴다).
 *
 * ★★ 값이 안 들어가는 자리는 **셋**이다. 셋 다 봐야 한다 — 하나라도 빼면 그쪽으로 다시 샌다.
 *   1) 홑/겹따옴표 문자열      `boxShadow: '0 4px 14px ink(.2)'`
 *   2) `${}` 안에 **중첩된** 문자열  `` `1px solid ${on ? C.accent : 'ink(.22)'}` ``
 *   3) 템플릿의 **글자 부분**    `` `box-shadow: 0 6px 16px acc(.24)` ``  ← ${} 를 안 씌운 것
 *   2026-09-21 에 셋이 차례로 나왔다(50 → 5 → 11곳). 1번만 보던 검사는 2·3번을 놓쳤다.
 * ★ 템플릿 안의 CSS 주석(`/* … *\/`)은 글일 뿐이라 건드리지 않는다.
 */
function plainStrings(src: string, base = 0, out: { line: number; text: string }[] = [], whole = src): { line: number; text: string }[] {
  let i = 0;
  const lineOf = (at: number) => whole.slice(0, at).split('\n').length;
  while (i < src.length) {
    const ch = src[i];
    if (ch === '`') {                                   // 템플릿
      i++;
      let litStart = i; let lit = '';
      const flushLiteral = () => { if (lit) out.push({ line: lineOf(base + litStart), text: lit }); lit = ''; };
      while (i < src.length) {
        if (src[i] === '\\') { lit += src.slice(i, i + 2); i += 2; continue; }
        if (src[i] === '$' && src[i + 1] === '{') {      // ${} 안은 코드 — 그 안의 문자열을 다시 본다
          flushLiteral();
          let d = 1; let j = i + 2; const st = j;
          while (j < src.length && d > 0) {
            if (src[j] === '\\') { j += 2; continue; }
            if (src[j] === '{') d++;
            else if (src[j] === '}') d--;
            j++;
          }
          plainStrings(src.slice(st, j - 1), base + st, out, whole);
          i = j; litStart = i; continue;
        }
        if (src[i] === '/' && src[i + 1] === '*') {      // 템플릿 안의 CSS 주석 — 글일 뿐이다
          flushLiteral();
          const j = src.indexOf('*/', i);
          i = j < 0 ? src.length : j + 2; litStart = i; continue;
        }
        if (src[i] === '`') { flushLiteral(); i++; break; }
        lit += src[i]; i++;
      }
      flushLiteral();
      continue;
    }
    if (ch === '/' && src[i + 1] === '/') { const j = src.indexOf('\n', i); i = j < 0 ? src.length : j; continue; }
    if (ch === '/' && src[i + 1] === '*') { const j = src.indexOf('*/', i); i = j < 0 ? src.length : j + 2; continue; }
    if (ch === "'" || ch === '"') {
      const q = ch; const start = i; let j = i + 1;
      while (j < src.length) {
        if (src[j] === '\\') { j += 2; continue; }
        if (src[j] === q || src[j] === '\n') break;
        j++;
      }
      out.push({ line: lineOf(base + start), text: src.slice(start + 1, j) });
      i = j + 1; continue;
    }
    i++;
  }
  return out;
}

test('색·토큰 함수가 값이 안 되는 자리에 글자로 남아 있지 않다', () => {
  const names = tokenFunctionNames();
  expect(names.length, 'ui.ts 에서 함수 이름을 하나도 못 읽었다 — 경로나 형식이 바뀌었는지 확인').toBeGreaterThan(0);
  const re = new RegExp(`\\b(?:${names.join('|')})\\(`);

  const bad: string[] = [];
  for (const file of sourceFiles(FE)) {
    const src = readFileSync(file, 'utf8');
    if (!re.test(src)) continue;
    for (const s of plainStrings(src)) {
      if (re.test(s.text)) bad.push(`${relative(FE, file)}:${s.line}  '${s.text}'`);
    }
  }

  expect(
    bad,
    [
      `스타일 문자열 안에서 색 함수가 안 불리고 글자로 남았습니다 (${bad.length}곳).`,
      `브라우저가 그 값을 통째로 버려서 막·테두리·글자색이 조용히 사라집니다.`,
      `고치는 법: '… ink(.2) …'  →  \`… \${ink(.2)} …\`   (따옴표를 백틱으로 바꾸고 \${} 로 감싼다)`,
      `검사하는 함수 목록(ui.ts 에서 읽음): ${names.join(', ')}`,
      ...bad,
    ].join('\n'),
  ).toEqual([]);
});
