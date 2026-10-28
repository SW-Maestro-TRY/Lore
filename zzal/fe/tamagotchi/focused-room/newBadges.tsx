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

/**
 * 점 자리 세 가지(2026-10-28 상훈님 확정 — 목업 27-B·28-D).
 * - tile: 방 타일(앨범 칸 등) 오른쪽 위 모서리에 걸친 큰 점(27-B).
 * - menu: 우상단 메뉴 단추 모서리에 걸친 큰 점(28-D). 단추 쪽은 FocusedRoom 이 같은 클래스로 직접 그린다.
 * - item: 메뉴·목록 항목 안 오른쪽 끝, 세로 가운데의 작은 점(28-D).
 * ★ 점만 그린다. 새것이 왔다는 문구(시스템 알림·캐릭터 대사)는 붙이지 않는다 — 출석은 "조용한 수령".
 */
export type NewDotPlace='tile'|'menu'|'item';

export function NewDot({count,locale,place}:{count:number;locale:ZzalLocale;place:NewDotPlace}){
 if(count<=0)return null;
 return <span className={`fr-new-dot fr-new-dot--${place}`} data-fr-new-dot data-fr-new-place={place} role="img" aria-label={newBadgeLabel(locale,count)}/>;
}
