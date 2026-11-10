import type { ZzalLocale } from '../../lib/locale';
import { CALL_ME_MAX, NAME_MAX, type CallMeError } from './characterCardRules';

/** 캐릭터 카드 문구. 시스템 안내 말투(캐릭터 말투 아님). 저장 성공 문구는 두지 않는다. */
export interface CardCopy {
  no: string; days: (n: number) => string; art: string; silhouette: string;
  callMe: string; undecided: string; editName: string; editCallMe: string;
  nameLabel: string; nameHint: string; callMeLabel: string; callMeHint: string;
  cancel: string; save: string; saving: string;
  back: string; backSub: string; empty: string; editDetail: string;
  sample: string; loading: string; loadFailed: string; retry: string; legacy: string;
  errors: { name: Partial<Record<CallMeError, string>>; callMe: Partial<Record<CallMeError, string>> };
  stale: string; traveling: string; uncertain: string; failed: string;
  labels: Record<'world' | 'job' | 'persona' | 'likes' | 'dislikes' | 'extra', string>;
}

const KO: CardCopy = {
  no: 'No. 001', days: n => `함께한 지 ${n}일`, art: '캐릭터 그림', silhouette: '그림을 준비하고 있어요',
  callMe: '나를 부르는 이름', undecided: '아직 정하지 않았어요', editName: '캐릭터 이름 고치기', editCallMe: '부르는 이름 고치기',
  nameLabel: '캐릭터 이름', nameHint: `${NAME_MAX}자까지 적을 수 있어요. 이름을 바꿔도 지난 대화와 일기는 그대로 남아요.`,
  callMeLabel: '나를 부르는 이름', callMeHint: `캐릭터가 나를 이렇게 불러요. ${CALL_ME_MAX}자까지 적을 수 있어요.`,
  cancel: '취소', save: '저장', saving: '저장 중…',
  back: '카드 뒷면', backSub: '상세 설정 여섯 칸', empty: '아직 입력하지 않았어요.', editDetail: '상세 설정 고치기',
  sample: '로미와 먼저 놀아 볼 수 있어요. 내 캐릭터의 설정은 알에서 알려 주세요.',
  loading: '설정을 불러오고 있어요…', loadFailed: '설정을 불러오지 못했어요. 다시 확인해 주세요.', retry: '다시 불러오기',
  legacy: '이전에 입력한 설정은 그대로 남아 있어요. 필요한 항목은 직접 추가할 수 있어요.',
  errors: {
    name: { empty: '이름을 한 글자 이상 적어 주세요.', tooLong: `${NAME_MAX}자까지 적을 수 있어요.` },
    callMe: {
      empty: '부르는 이름을 한 글자 이상 적어 주세요.', tooLong: `${CALL_ME_MAX}자까지 적을 수 있어요.`,
      multiple: '한 가지만 적어 주세요. 예: 언니',
      nameless: '부르는 이름을 적어 주세요. 이름 없이 부르길 원하면 상세 설정에서 건너뛰기를 골라 주세요.',
    },
  },
  stale: '다른 곳에서 설정이 바뀌었어요. 최신 값을 불러왔어요. 다시 저장해 주세요.',
  traveling: '여행에서 돌아오면 고칠 수 있어요.',
  uncertain: '저장됐는지 확인하지 못했어요. 같은 내용으로 한 번 더 저장해 주세요.',
  failed: '저장하지 못했어요. 잠시 뒤에 다시 저장해 주세요.',
  labels: { world: '세계관', job: '직업·소속', persona: '성격', likes: '좋아하는 것', dislikes: '싫어하는 것', extra: '그 밖에' },
};
const EN: CardCopy = {
  no: 'No. 001', days: n => `${n} days together`, art: 'Character art', silhouette: 'Art is being prepared',
  callMe: 'What they call you', undecided: 'Not decided yet', editName: 'Edit character name', editCallMe: 'Edit what they call you',
  nameLabel: 'Character name', nameHint: `Up to ${NAME_MAX} characters. Earlier chats and diary entries stay as they are.`,
  callMeLabel: 'What they call you', callMeHint: `Your character calls you this. Up to ${CALL_ME_MAX} characters.`,
  cancel: 'Cancel', save: 'Save', saving: 'Saving…',
  back: 'Card back', backSub: 'Six detailed settings', empty: 'Not entered', editDetail: 'Edit detailed settings',
  sample: 'You can play with Romi first. Tell us about your own character from the egg.',
  loading: 'Loading settings…', loadFailed: 'Couldn’t load settings. Try again.', retry: 'Load again',
  legacy: 'Your earlier settings are still here. You can add more details yourself.',
  errors: {
    name: { empty: 'Enter at least one character.', tooLong: `Up to ${NAME_MAX} characters.` },
    callMe: {
      empty: 'Enter at least one character.', tooLong: `Up to ${CALL_ME_MAX} characters.`,
      multiple: 'Enter just one. For example: Sis',
      nameless: 'Enter a name. To skip being called by name, choose Skip in detailed settings.',
    },
  },
  stale: 'Settings changed elsewhere. The latest values are loaded. Save again.',
  traveling: 'You can edit this after they return from their trip.',
  uncertain: 'Couldn’t confirm the save. Save the same content once more.',
  failed: 'Couldn’t save. Try again in a moment.',
  labels: { world: 'World', job: 'Work or affiliation', persona: 'Personality', likes: 'Likes', dislikes: 'Dislikes', extra: 'Anything else' },
};
const JA: CardCopy = {
  no: 'No. 001', days: n => `一緒に${n}日`, art: 'キャラクターの絵', silhouette: '絵を準備しています',
  callMe: 'あなたの呼び方', undecided: 'まだ決めていません', editName: 'キャラクターの名前を変更', editCallMe: '呼び方を変更',
  nameLabel: 'キャラクターの名前', nameHint: `${NAME_MAX}文字まで入力できます。名前を変えても過去の会話や日記はそのまま残ります。`,
  callMeLabel: 'あなたの呼び方', callMeHint: `キャラクターがあなたをこう呼びます。${CALL_ME_MAX}文字まで入力できます。`,
  cancel: 'キャンセル', save: '保存', saving: '保存中…',
  back: 'カードの裏面', backSub: '詳細設定6項目', empty: '未入力', editDetail: '詳細設定を変更',
  sample: 'まずはロミと遊べます。自分のキャラクターの設定は卵から教えてください。',
  loading: '設定を読み込んでいます…', loadFailed: '設定を読み込めませんでした。もう一度お試しください。', retry: 'もう一度読み込む',
  legacy: '以前の設定はそのまま残っています。必要な項目を自分で追加できます。',
  errors: {
    name: { empty: '1文字以上入力してください。', tooLong: `${NAME_MAX}文字まで入力できます。` },
    callMe: {
      empty: '1文字以上入力してください。', tooLong: `${CALL_ME_MAX}文字まで入力できます。`,
      multiple: '1つだけ入力してください。例: お姉ちゃん',
      nameless: '呼び方を入力してください。名前なしで呼ばれたい場合は詳細設定でスキップを選んでください。',
    },
  },
  stale: '別の場所で設定が変わりました。最新の値を読み込みました。もう一度保存してください。',
  traveling: '旅行から戻ると変更できます。',
  uncertain: '保存できたか確認できませんでした。同じ内容でもう一度保存してください。',
  failed: '保存できませんでした。少し後でもう一度保存してください。',
  labels: { world: '世界・舞台', job: '仕事・所属', persona: '性格', likes: '好きなもの', dislikes: '苦手・嫌いなもの', extra: 'その他' },
};

export function cardCopy(locale: ZzalLocale): CardCopy { return locale === 'en' ? EN : locale === 'ja' ? JA : KO; }
