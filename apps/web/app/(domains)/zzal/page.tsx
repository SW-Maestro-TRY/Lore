// /zzal — 자캐 다마고치.
//
// 2026-08-30 시안을 스크랩북으로 확정하고 이 화면을 기반으로 개발에 들어갔다.
// 레트로 카트리지 시안은 지웠다(git 히스토리에 남아 있다) — 화면이 아직 절반도
// 안 만들어진 단계에서 두 벌을 병행하면 앞으로 만들 화면이 전부 두 배가 되기 때문이다.
// 기능이 다 차면 그때 스킨으로 되살린다.
//
// 옛 랜딩(Hero·HowItWorks·CharacterCreator)은 /zzal/landing 에 남아 있다.
'use client';

import TamagotchiScreen from '@zzal/tamagotchi/TamagotchiScreen';
import Scrapbook from '@zzal/tamagotchi/skins/Scrapbook';

export default function Page() {
  return <TamagotchiScreen skin={Scrapbook} name="scrapbook" />;
}
