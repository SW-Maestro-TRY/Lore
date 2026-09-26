// 모든 검사가 시작되기 **전에** 한 번 — 밟기 전에 전제를 확인한다.
//
// ★★ 왜 이 검사가 필요한가 (2026-09-22 실측)
//   합본 브랜치에서 `baby`·`baby-resume` 둘만 깨졌다. 코드는 프론트 브랜치와 같았고, 범인은
//   **워크트리에 `apps/web/public/zzal` 이 없던 것**이었다. 그런데 증상은 그림과 아무 상관 없어
//   보이는 *"튜토리얼 7번 칸이 안 넘어간다"* 로 나타난다 — 원인에 닿기까지 사람이 오래 걸린다.
//
//   사슬은 이렇다:
//     1. e2e 는 `NEXT_PUBLIC_CDN_BASE=`(빈 값)로 띄운다(playwright.config.ts) →
//        `assetUrl`(zzal/fe/lib/assets.ts)이 그림을 **public/ 에서 직접** 받는다.
//     2. `apps/web/public/zzal/` 은 **.gitignore** 에 있다 → 새 워크트리에는 아예 없다.
//     3. 도감의 **저장**(useAlbum.ts `save`)은 그림을 실제로 받아 보고, 404 면 `onShared` 를
//        **안 부른다.**
//     4. 튜토리얼 7번(공유) 칸을 넘기는 것은 그 호출뿐이다(mockPetServer `advanceTutorial('SHARE')`).
//        → 부름이 `baby:SHARE` 에 멈추고, 다음 칸을 기대한 단언이 깨진다.
//
// ★ **통과시키지 않는다.** 없으면 또렷하게 실패하는 것이 이 파일의 목적이다 —
//   원인만 말하고 처방이 없으면 절반이라, 무엇을 어디에 걸면 되는지까지 적는다.
import { existsSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';

const WEB = resolve(__dirname, '..');
const NEED = join(WEB, 'public', 'zzal');

export default function globalSetup(): void {
  // `existsSync` 는 심링크를 따라간다 — 끊긴 링크도 여기서 걸린다.
  const empty = existsSync(NEED) && readdirSync(NEED).length === 0;
  if (existsSync(NEED) && !empty) {
    return;
  }
  throw new Error(
    [
      '',
      `public/zzal 이 ${empty ? '비어 있습니다' : '없습니다'} — 워크트리에 심링크를 거십시오`,
      '',
      `  없는 곳: ${NEED}`,
      '',
      '  고치는 법 — 그림이 있는 워크트리(보통 메인 체크아웃)의 public 을 통째로 겁니다.',
      `    ln -s <그림이 있는 워크트리>/apps/web/public ${join(WEB, 'public')}`,
      '',
      '  왜 필요한가 — e2e 는 CDN 을 비워 띄워서 그림을 public/ 에서 직접 받습니다.',
      '  apps/web/public/zzal 은 .gitignore 라 새 워크트리에는 없고, 그림이 404 나면',
      '  도감의 "저장" 이 공유를 안 알려 튜토리얼 7번 칸이 안 넘어갑니다',
      '  (baby·baby-resume 이 baby:SHARE 에서 멈춥니다). 2026-09-22 실측.',
      '',
    ].join('\n'),
  );
}
