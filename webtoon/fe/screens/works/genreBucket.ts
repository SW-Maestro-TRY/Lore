import { GENRE_QUICK } from "../../lib/wizardData";

/* 둘러보기 장르 칸이 쓰는 갈래.
 *
 * 작품 카드의 장르(`RunCard.genre`)는 사용자가 고른 칩이 아니라 이야기를 만들 때
 * 모델이 적은 글이다. 그래서 「아이돌 / 코믹 성장」「Romance Fantasy, Mystery」
 * 「능력 배틀 판타지·계약물」처럼 작품마다 다르고 영어로도 온다. 그 글을 그대로
 * 칩으로 만들면 작품 수만큼 장르가 생긴다.
 *
 * 여기서 만들기 화면의 장르 목록(GENRE_QUICK)에 맞춰 한 갈래로 묶는다. 위에서부터
 * 처음 걸리는 갈래 하나다 — 「로맨스 판타지」가 「로맨스」·「판타지」보다, 소재가
 * 뚜렷한 갈래(아이돌·무협…)가 분위기 갈래(개그·액션)보다, 분위기 갈래가 넓은
 * 「판타지」보다 먼저 온다. 어디에도 안 걸리면 「기타」. */
const RULES: [bucket: string, words: string[]][] = [
  ["로맨스 판타지", ["로맨스 판타지", "로맨스판타지", "로판", "romance fantasy"]],
  ["무협", ["무협", "무림", "wuxia", "murim", "martial arts"]],
  ["헌터·게이트", ["헌터", "게이트", "hunter", "gate"]],
  ["마법학교", ["마법학교", "마법 학교", "magic school", "magic academy"]],
  ["게임 판타지", ["게임", "가상현실", "game", "vr"]],
  ["센티넬", ["센티넬", "sentinel"]],
  ["오메가버스", ["오메가", "omega"]],
  ["아이돌", ["아이돌", "idol"]],
  ["히어로", ["히어로", "hero"]],
  ["스릴러", ["스릴러", "추리", "미스터리", "호러", "공포", "thriller", "mystery", "horror"]],
  ["로맨스", ["로맨스", "연애", "romance"]],
  ["개그", ["개그", "코미디", "코믹", "comedy", "gag"]],
  ["액션", ["액션", "배틀", "action", "battle"]],
  ["일상", ["일상", "slice of life"]],
  ["판타지", ["판타지", "fantasy"]],
];

export const OTHER_GENRE = "기타";

/** 칸에 보일 순서 — 만들기 화면 목록 순서에, 거기 없는 갈래를 뒤에 붙인다. */
export const GENRE_ORDER = [
  ...GENRE_QUICK,
  ...RULES.map(([b]) => b).filter((b) => !GENRE_QUICK.includes(b)),
  OTHER_GENRE,
];

/* 영어 낱말은 낱말 단위로만 본다 — 그냥 포함으로 보면 「survival」에 vr, 「heroine」에
   hero 가 걸린다. 한글은 붙여 쓰는 말(「코믹판타지」)이 많아 포함으로 본다. */
const hits = (s: string, w: string) =>
  /^[a-z ]+$/.test(w) ? new RegExp(`\\b${w}(s|es)?\\b`).test(s) : s.includes(w);

export function genreBucket(raw: string | undefined | null): string {
  const s = (raw || "").trim().toLowerCase();
  if (!s) return "";
  for (const [bucket, words] of RULES) {
    if (words.some((w) => hits(s, w))) return bucket;
  }
  return OTHER_GENRE;
}
