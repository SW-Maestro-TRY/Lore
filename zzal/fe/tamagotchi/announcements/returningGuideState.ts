import type {PetDetail} from '../../lib/pet';

export const RETURNING_GUIDE_VERSION = '20261023-v1';
export const returningGuideKey = (userId:number,petId:number) => `zzal-returning-guide:${RETURNING_GUIDE_VERSION}:${userId}:${petId}`;
export function returningGuideEligible(pet:PetDetail|null|undefined):boolean {
  if(!pet || pet.phase!=='ALIVE' || !pet.hatchedAt) return false;
  const completed=pet.firstIntro ? pet.firstIntro.key==='COMPLETE' : !!pet.clock?.clockStartedAt && !pet.tutorial;
  return completed;
}
export function returningGuideAutomatic(pet:PetDetail|null|undefined):boolean {
  return returningGuideEligible(pet) && (!pet?.firstIntro || pet.firstIntro.version<4);
}

export const returningCopy = {
 ko:{title:'대화가 새로운 움직임으로 이어져요',update:'캐릭터 설정을 다시 다듬고, 일상과 취향을 채팅으로 나눠 보세요. 전에 나눈 대화와 받은 선물은 그대로예요.',intro:'이야기를 나눠 볼까요?',memory:'이야기가 쌓이면',reward:'3번째 출석부터 캐릭터에 맞춘 8가지 움직임을 하나씩 선물해요.',settings:'캐릭터 설정을 다시 다듬어요',settingsBody:'메뉴 → 캐릭터 설정에서 성격과 배경, 나를 부르는 이름을 언제든 수정할 수 있어요.',edit:'캐릭터 설정 수정하기',chat:'채팅으로 돌아가기',room:'방으로 돌아가기',skip:'지금은 건너뛰기',next:'다음',back:'이전',replay:'새 안내 다시 보기',close:'닫기',storage:'이 브라우저에 안내 확인을 저장하지 못했어요. 다음 방문에 다시 보일 수 있어요.',locked:'자는 중이거나 여행 중에는 대화할 수 없어요. 설정은 지금 수정할 수 있어요.'},
 en:{title:'Conversations become new motions',update:'Refresh your character settings and share your day and interests in chat. Your past chats and gifts stay with you.',intro:'Let’s share a story',memory:'As your stories grow',reward:'Starting with your third attendance, receive eight custom motions, one at a time.',settings:'Refresh character settings',settingsBody:'Open Menu → Character settings to edit their personality, background, and what they call you at any time.',edit:'Edit character settings',chat:'Back to chat',room:'Back to room',skip:'Skip for now',next:'Next',back:'Previous',replay:'See the new guide again',close:'Close',storage:'Could not save this guide’s status in this browser. It may appear again next time.',locked:'Chat is unavailable during sleep or a trip. You can still edit settings now.'},
 ja:{title:'会話が新しい動きにつながります',update:'キャラクターの設定を見直して、日常や好きなことを会話で伝えてみましょう。これまでの会話や贈り物はそのままです。',intro:'お話ししてみましょう',memory:'お話が重なると',reward:'3回目の出席から、キャラクターに合わせた8種類の動きを一つずつ贈ります。',settings:'キャラクター設定を見直す',settingsBody:'メニュー → キャラクター設定から、性格や背景、あなたの呼び名をいつでも変更できます。',edit:'キャラクター設定を変更',chat:'会話に戻る',room:'部屋に戻る',skip:'今はスキップ',next:'次へ',back:'前へ',replay:'新しい案内をもう一度見る',close:'閉じる',storage:'このブラウザに確認状況を保存できませんでした。次回また表示される場合があります。',locked:'睡眠中や旅行中は会話できません。設定は今でも変更できます。'},
} as const;
