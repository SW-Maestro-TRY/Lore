import type { ZzalLocale } from './localeRouting';

/**
 * 맞춤 동작 제목 표시용 사전: 저장된 제목(어느 언어든) → 현재 locale 제목.
 * 2026-10 이전 계획기는 언어 지시가 없어 일부 계획이 펫 언어와 다른 제목으로 얼려졌다
 * (잡 1·11 = ko 펫인데 영어, 잡 2 = en 펫인데 한국어).
 * 계획 JSON은 해시로 고정돼 DB에서 고칠 수 없으므로, 화면에서만 번역해 보인다.
 * 새 계획은 서버가 제목을 펫 언어로 쓰고 검사하므로 이 사전은 옛 계획분만 담는다.
 * 사전에 없는 제목이나 locale 번역이 없는 칸은 원문 그대로 보인다.
 */
type Titles = Partial<Record<ZzalLocale, string>>;
const ENTRIES: Titles[] = [
  // 잡 1(펫 1)·잡 11(펫 163): 영어 원제
  { en: 'Mock-Heroic Entrance', ko: '거창한 등장', ja: '大げさな登場' },
  { en: 'Mimic the Invisible Maestro', ko: '보이지 않는 지휘자 흉내', ja: '見えない指揮者のまね' },
  { en: 'Careful Gem Inspection', ko: '보석 꼼꼼히 살피기', ja: '宝石をじっくり見る' },
  { en: 'Royal Cloak Adjustment', ko: '망토 매무새 다듬기', ja: 'マントを整える' },
  { en: 'Quiet Window Watch', ko: '조용히 창밖 보기', ja: '静かに窓の外を見る' },
  { en: 'Tiny Victory Salute', ko: '작은 승리의 경례', ja: '小さな勝利の敬礼' },
  { en: 'Dramatic Rest', ko: '극적인 휴식', ja: '大げさなひと休み' },
  { en: 'Surprised Mirror Greeting', ko: '거울 보고 깜짝 인사', ja: '鏡にびっくりあいさつ' },
  { en: 'Chocolate Victory Pose', ko: '초콜릿 승리 포즈', ja: 'チョコの勝利ポーズ' },
  { en: 'Cloudbound Leap', ko: '구름 위로 점프', ja: '雲までジャンプ' },
  { en: 'Bunny Greeting', ko: '토끼 인사', ja: 'うさぎのあいさつ' },
  { en: 'Moonlit Rest Stop', ko: '달빛 아래 쉬어 가기', ja: '月明かりでひと休み' },
  { en: 'Playful Rabbit Challenge', ko: '장난꾸러기 토끼 도전', ja: 'いたずらうさぎの挑戦' },
  { en: 'Rainy Umbrella Spin', ko: '빗속 우산 돌리기', ja: '雨の日の傘くるくる' },
  { en: 'Bug Avoidance Dash', ko: '벌레 피해 달리기', ja: '虫をよけてダッシュ' },
  { en: 'Map-Marking Break', ko: '지도 표시하며 쉬기', ja: '地図に印をつけてひと休み' },
  // 잡 2(펫 139, en 펫): 한국어 원제
  { ko: '잔해 위의 당당한 경계', en: 'Proud Watch on the Rubble', ja: 'がれきの上で堂々と見張り' },
  { ko: '비밀 간식 보물찾기', en: 'Secret Snack Treasure Hunt', ja: 'ひみつのおやつ宝探し' },
  { ko: '무음 경고의 손짓', en: 'Silent Warning Signal', ja: '声を出さない合図' },
  { ko: '꼬리 끝의 작은 춤', en: 'Little Tail-Tip Dance', ja: 'しっぽの先の小さなダンス' },
  { ko: '안전한 물자 정리', en: 'Sorting Safe Supplies', ja: '安全な物資の整理' },
  { ko: '벌레 없는 안전 확인', en: 'Bug-Free Safety Check', ja: '虫がいないか安全確認' },
  { ko: '낮은 목소리의 위로', en: 'Soft-Spoken Comfort', ja: '小さな声でなぐさめる' },
  { ko: '황무지의 깃발 세우기', en: 'Planting a Flag in the Wasteland', ja: '荒れ地に旗を立てる' },
];
const BY_STORED = new Map<string, Titles>();
for (const entry of ENTRIES) for (const title of Object.values(entry)) if (title) BY_STORED.set(title, entry);

/** 저장된 제목이 사전에 있으면 현재 locale 제목으로, 없으면 원문. */
export function localizeCustomMotionTitle(title: string, locale: ZzalLocale): string {
  return BY_STORED.get(title.trim())?.[locale] ?? title;
}
