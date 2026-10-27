import type {PetDetail} from '../../lib/pet';

export const RETURNING_GUIDE_VERSION = '20261023-required-v2';
export const returningGuideKey = (userId:number,petId:number) => `zzal-returning-guide:${RETURNING_GUIDE_VERSION}:${userId}:${petId}`;
export function returningGuideEligible(pet:PetDetail|null|undefined):boolean {
  if(!pet || pet.phase!=='ALIVE' || !pet.hatchedAt) return false;
  const completed=pet.firstIntro ? pet.firstIntro.key==='COMPLETE' : !!pet.clock?.clockStartedAt && !pet.tutorial;
  return completed;
}
export function returningGuideAutomatic(pet:PetDetail|null|undefined):boolean {
  return returningGuideEligible(pet) && (!pet?.firstIntro || pet.firstIntro.version<4);
}

/** "나중에 할게요" stays on this browser for the local calendar day only; server progress is untouched. */
export const returningGuideLaterKey = (guideKey:string) => `${guideKey}:later`;
export function localDay(now:Date=new Date()):string {
  return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
}
type DayStorage = Pick<Storage,'getItem'|'setItem'>;
export function browserStorage():DayStorage|null {
  try { return typeof window==='undefined' ? null : window.localStorage; } catch { return null; }
}
export function returningGuideLaterToday(storage:DayStorage|null|undefined, guideKey:string, now:Date=new Date()):boolean {
  if(!storage || !guideKey) return false;
  try { return storage.getItem(returningGuideLaterKey(guideKey))===localDay(now); } catch { return false; }
}
export function deferReturningGuide(storage:DayStorage|null|undefined, guideKey:string, now:Date=new Date()):boolean {
  if(!storage || !guideKey) return false;
  try { storage.setItem(returningGuideLaterKey(guideKey), localDay(now)); return true; } catch { return false; }
}

export const returningCopy = {
 ko:{showExamples:'대화 예시 보기',loading:'안내를 불러오고 있어요.',failed:'안내를 저장하거나 불러오지 못했어요. 다시 확인해 주세요.',retry:'다시 확인하기',title:'대화가 새로운 움직임으로 이어져요',update:'캐릭터 설정을 다시 다듬고, 일상과 취향을 채팅으로 나눠 보세요. 전에 나눈 대화와 받은 선물은 그대로예요.',intro:'이야기를 나눠 볼까요?',memory:'이야기가 쌓이면',reward:'3번째 출석부터 캐릭터에 맞춘 8가지 움직임을 하나씩 선물해요.',settings:'캐릭터 설정을 다시 다듬어요',settingsBody:'캐릭터 정보 → 설정 수정하기에서 성격과 배경, 나를 부르는 이름을 확인해 주세요. 내용을 그대로 두고 저장해도 괜찮아요.',edit:'캐릭터 정보 확인하기',chat:'채팅으로 돌아가기',room:'방으로 돌아가기',skip:'지금은 건너뛰기',later:'나중에 할게요',next:'다음',back:'이전',replay:'새 안내 다시 보기',close:'닫기',storage:'이 브라우저에 안내 확인을 저장하지 못했어요. 다음 방문에 다시 보일 수 있어요.',locked:'자는 중이거나 여행 중에는 대화할 수 없어요. 설정은 지금 수정할 수 있어요.'},
 en:{showExamples:'See conversation examples',loading:'Loading your guide.',failed:'Could not load or save your guide. Please try again.',retry:'Try again',title:'Conversations become new motions',update:'Refresh your character settings and share your day and interests in chat. Your past chats and gifts stay with you.',intro:'Let’s share a story',memory:'As your stories grow',reward:'Starting with your third attendance, receive eight custom motions, one at a time.',settings:'Refresh character settings',settingsBody:'Open Character info → Edit settings to review personality, background, and what they call you. You can confirm and keep your current settings.',edit:'Review character info',chat:'Back to chat',room:'Back to room',skip:'Skip for now',later:'Maybe later',next:'Next',back:'Previous',replay:'See the new guide again',close:'Close',storage:'Could not save this guide’s status in this browser. It may appear again next time.',locked:'Chat is unavailable during sleep or a trip. You can still edit settings now.'},
 ja:{showExamples:'会話の例を見る',loading:'案内を読み込んでいます。',failed:'案内を保存または読み込めませんでした。もう一度確認してください。',retry:'もう一度確認',title:'会話が新しい動きにつながります',update:'キャラクターの設定を見直して、日常や好きなことを会話で伝えてみましょう。これまでの会話や贈り物はそのままです。',intro:'お話ししてみましょう',memory:'お話が重なると',reward:'3回目の出席から、キャラクターに合わせた8種類の動きを一つずつ贈ります。',settings:'キャラクター設定を見直す',settingsBody:'キャラクター情報 → 設定を変更するから、性格や背景、あなたの呼び名を確認してください。変更せずに確認して保存することもできます。',edit:'キャラクター情報を確認',chat:'会話に戻る',room:'部屋に戻る',skip:'今はスキップ',later:'あとでにする',next:'次へ',back:'前へ',replay:'新しい案内をもう一度見る',close:'閉じる',storage:'このブラウザに確認状況を保存できませんでした。次回また表示される場合があります。',locked:'睡眠中や旅行中は会話できません。設定は今でも変更できます。'},
} as const;
