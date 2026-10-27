'use client';
import type {PetDetail} from '../../lib/pet';
import type {ZzalLocale} from '../../lib/locale';
import './new-badges.css';

/**
 * "새 일지 · 새 출석 선물" 빨간 점(2026-10-27 상훈님 결정).
 *
 * ★ 두 갈래는 서로 다른 길에 붙는다 — 이 방에서 출석은 우상단 메뉴 안에, 일지는 아래 「앨범」 칸 안에 있다.
 *   그래서 메뉴 단추·「출석」 = 새 선물, 「앨범」 칸·「일지」 = 안 읽은 일지. 점이 가리키는 곳에 실제로 새것이 있어야 한다.
 * ★ 수는 서버가 센다(PetDetail.newRewardCount · unreadDiaryCount). 화면은 세지 않는다 — 기기마다 달라지면 안 된다.
 * ★ 튜토리얼 안내 중(guided)에는 그리지 않는다. 안내 하이라이트와 점이 같은 자리를 두고 다투면 안내가 흐려진다.
 */
export function newBadgeCounts(pet:PetDetail|null|undefined,hidden:boolean){
 if(hidden||!pet||pet.phase!=='ALIVE')return{rewards:0,diary:0};
 const intro=pet.firstIntro;
 if(pet.tutorial||intro&&!intro.completed)return{rewards:0,diary:0};
 return{rewards:Math.max(0,pet.newRewardCount??0),diary:Math.max(0,pet.unreadDiaryCount??0)};
}

export function newBadgeLabel(locale:ZzalLocale,count:number){
 return locale==='en'?`${count} new`:locale==='ja'?`新着${count}件`:`새 항목 ${count}개`;
}

/** 8px 빨간 원. 단추 안 오른쪽 위에 붙는다 — 단추는 data-fr-new 로 기준 상자가 된다. */
export function NewDot({count,locale}:{count:number;locale:ZzalLocale}){
 if(count<=0)return null;
 return <span className="fr-new-dot" data-fr-new-dot role="img" aria-label={newBadgeLabel(locale,count)}/>;
}

/** 선물이 도착한 순간 아이가 하는 한마디. 생성 호출 없는 고정 문구다(과금 0). */
export function giftArrivedLine(locale:ZzalLocale){
 return locale==='en'?'A gift has arrived. Could you check it in Attendance?':locale==='ja'?'贈り物が届いたよ。出席で見てくれる？':'선물이 도착했어. 출석에서 봐 줄래?';
}
