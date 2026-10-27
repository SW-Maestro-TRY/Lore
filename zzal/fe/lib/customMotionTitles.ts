import type { ZzalLocale } from './localeRouting';

/**
 * 맞춤 동작 제목 표시용 사전.
 * 2026-10 이전 계획기는 언어 지시가 없어 일부 계획(잡 1·11)이 영어 제목으로 얼려졌다.
 * 계획 JSON은 해시로 고정돼 DB에서 고칠 수 없으므로, 화면에서만 원제를 번역해 보인다.
 * 새 계획은 서버가 펫 언어로 쓰고 검사하므로 이 사전은 더 늘리지 않는다.
 * 사전에 없는 제목은 원문 그대로 보인다.
 */
const TITLES: Record<string, { ko: string; ja: string }> = {
  'Mock-Heroic Entrance': { ko: '거창한 등장', ja: '大げさな登場' },
  'Mimic the Invisible Maestro': { ko: '보이지 않는 지휘자 흉내', ja: '見えない指揮者のまね' },
  'Careful Gem Inspection': { ko: '보석 꼼꼼히 살피기', ja: '宝石をじっくり見る' },
  'Royal Cloak Adjustment': { ko: '망토 매무새 다듬기', ja: 'マントを整える' },
  'Quiet Window Watch': { ko: '조용히 창밖 보기', ja: '静かに窓の外を見る' },
  'Tiny Victory Salute': { ko: '작은 승리의 경례', ja: '小さな勝利の敬礼' },
  'Dramatic Rest': { ko: '극적인 휴식', ja: '大げさなひと休み' },
  'Surprised Mirror Greeting': { ko: '거울 보고 깜짝 인사', ja: '鏡にびっくりあいさつ' },
  'Chocolate Victory Pose': { ko: '초콜릿 승리 포즈', ja: 'チョコの勝利ポーズ' },
  'Cloudbound Leap': { ko: '구름 위로 점프', ja: '雲までジャンプ' },
  'Bunny Greeting': { ko: '토끼 인사', ja: 'うさぎのあいさつ' },
  'Moonlit Rest Stop': { ko: '달빛 아래 쉬어 가기', ja: '月明かりでひと休み' },
  'Playful Rabbit Challenge': { ko: '장난꾸러기 토끼 도전', ja: 'いたずらうさぎの挑戦' },
  'Rainy Umbrella Spin': { ko: '빗속 우산 돌리기', ja: '雨の日の傘くるくる' },
  'Bug Avoidance Dash': { ko: '벌레 피해 달리기', ja: '虫をよけてダッシュ' },
  'Map-Marking Break': { ko: '지도 표시하며 쉬기', ja: '地図に印をつけてひと休み' },
};

/** 영어 원제가 사전에 있으면 ko/ja로 번역, en이거나 사전에 없으면 원문. */
export function localizeCustomMotionTitle(title: string, locale: ZzalLocale): string {
  if (locale === 'en') return title;
  const entry = TITLES[title.trim()];
  return entry ? entry[locale] : title;
}
