'use client';
import {interactionMetrics} from '../focused-room/interactionMetrics';
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { getDiary, readDiary, type DiaryEntry, type DiaryPage } from '../../lib/pet';
import { useZzalLocale } from '../../lib/locale';
import { C, TAP_MIN, radius } from './ui';
import { useLive } from './useHatch';
import {josa} from '../constants';
import { formatInZone } from '../../lib/kst';

const copy = {
  ko: { title:'앨범', album:'앨범', diaryMenu:'일지', receivedMotions:'받은 동작', albumDescription:'함께한 순간을 모아 봐요', albumGuide:'빛나는 동작 하나를 눌러 크게 보세요. 저장·공유 버튼도 확인할 수 있어요.', albumViewedGuide:'동작을 살펴봤어요. 앨범 메뉴로 돌아가 다음 안내를 이어가요.', frameGuide:'저장·공유는 원할 때 쓰면 돼요. 버튼을 확인한 뒤, 뒤로 가서 다음 안내를 이어가요.', diaryDescription:'아이가 남긴 하루를 읽어요', roomDescription:'가진 배경으로 방을 꾸며요', roomMenu:'방', preview:'선택됨', applying:'적용 중…', sample:'여기서는 연습용 방을 바꿔 볼 수 있어요.', attendance:'번째 만남', gifts:'받은 선물', backgrounds:'방 꾸미기', diary:'아이의 일지', back:'앨범 메뉴로', close:'일지 목록으로', loading:'불러오는 중이에요…', empty:'아직 도착한 일지가 없어요.', routineEmpty:'앞으로 남길 일지도 여기에 모여요.', birthStory:'함께할 이야기가 여기서 시작됐어요.', pending:'아이가 일지를 쓰고 있어요.', preparing:'아직 준비 중인 선물이 있어요. 준비되면 전해 드릴게요.', none:'아직 받은 선물이 없어요.', apply:'이 배경 쓰기', selected:'사용 중', retry:'다시 시도', error:'연결하지 못했어요. 다시 시도해 주세요.', ackError:'읽음 표시를 저장하지 못했어요.', more:'이전 일지 더 보기', unread:'새 일지', room:'기본 방', next:'밤잠을 자고 다시 깨어난 뒤 만나면, 다음 출석으로 이어져요.' },
  en: { title:'Album', album:'Album', diaryMenu:'Journal', receivedMotions:'Received motions', albumDescription:'Moments together', albumGuide:'Open the glowing motion to see it up close and find Save and Share.', albumViewedGuide:'You’ve seen a motion. Return to the album menu to continue.', frameGuide:'Save or share whenever you like. Check the buttons, then go back to continue the guide.', diaryDescription:'Read your character’s days', roomDescription:'Decorate with backgrounds you own', roomMenu:'Room', preview:'Selected', applying:'Applying…', sample:'Try changing the practice room here.', attendance:'visits together', gifts:'Received gifts', backgrounds:'Decorate room', diary:'Character journal', back:'Album menu', close:'Back to entries', loading:'Loading…', empty:'No journal entries have arrived yet.', routineEmpty:'Future journal entries will gather here too.', birthStory:'Your story together began here.', pending:'Your character is writing a journal entry.', preparing:'Some gifts are still being prepared. They will arrive when ready.', none:'No gifts received yet.', apply:'Use this background', selected:'In use', retry:'Try again', error:'Could not connect. Please try again.', ackError:'Could not save the read status.', more:'Older entries', unread:'New entry', room:'Original room', next:'Meet again after a night’s sleep and waking up to continue your attendance.' },
  ja: { title:'アルバム', album:'アルバム', diaryMenu:'日記', receivedMotions:'届いた動き', albumDescription:'一緒の瞬間を集めよう', albumGuide:'光っている動きをひとつ押して大きく見てみましょう。保存・共有ボタンも確認できます。', albumViewedGuide:'動きを見られましたね。アルバムメニューに戻って、次の案内へ進みましょう。', frameGuide:'保存や共有は、したいときに使えます。ボタンを確認したら、戻って次の案内へ進みましょう。', diaryDescription:'この子の一日を読もう', roomDescription:'持っている背景で部屋を飾ろう', roomMenu:'部屋', preview:'選択中', applying:'適用中…', sample:'ここでは練習用の部屋を変えられます。', attendance:'回目の出会い', gifts:'届いた贈り物', backgrounds:'部屋を飾る', diary:'この子の日記', back:'アルバムメニューへ', close:'日記一覧へ', loading:'読み込み中…', empty:'まだ日記は届いていません。', routineEmpty:'これからの日記もここに集まります。', birthStory:'一緒につづる物語が、ここから始まりました。', pending:'この子が日記を書いています。', preparing:'準備中の贈り物があります。準備できたらお届けします。', none:'まだ贈り物はありません。', apply:'この背景を使う', selected:'使用中', retry:'もう一度', error:'接続できませんでした。もう一度お試しください。', ackError:'既読を保存できませんでした。', more:'前の日記を見る', unread:'新しい日記', room:'元の部屋', next:'夜に眠って目覚めたあとに会うと、次の出席に進みます。' },
};
export function useRewardsCopy() { return copy[useZzalLocale()]; }
const button: CSSProperties = { minHeight:TAP_MIN, padding:'8px 12px', border:`1px solid ${C.lineHard}`, borderRadius:radius.md, background:C.paper, color:C.ink, font:'inherit', cursor:'pointer', overflowWrap:'anywhere' };

export default function RewardsJournal({ onClose }: { onClose: () => void }) {
  const live = useLive(); const metrics=interactionMetrics(live,false,'journal'); const t = useRewardsCopy(); const locale = useZzalLocale();
  const [page,setPage] = useState<DiaryPage|null>(null); const [entry,setEntry] = useState<DiaryEntry|null>(null);
  const [busy,setBusy] = useState(false); const [error,setError] = useState(false); const [ackError,setAckError] = useState(false);
  const ackPending = useRef(new Set<number>());
  const introSeen=useRef(false);
  const diaryIntro=live.pet?.firstIntro?.key==='DIARY_INTRO';
  // A factual milestone from the pet, not an unread/generated diary entry.
  const bornAt=live.pet?.hatchedAt;
  const bornName=live.pet?.name?.trim();
  const hasBirth=!!bornName&&!!bornAt&&!Number.isNaN(Date.parse(bornAt));
  const birthTitle=locale==='en'?`${bornName} was born.`:locale==='ja'?`${bornName}が生まれました。`:`${bornName}${josa(bornName??'','이','가')} 태어났어요.`;
  const lock = useRef(false); const active = useRef(true); const petId = live.petId;
  useEffect(() => { active.current=true; return () => { active.current=false; }; },[]);
  useEffect(() => {
    if (petId == null) return;
    const controller = new AbortController(); setBusy(true); setError(false); setPage(null); setEntry(null);
    getDiary(petId,undefined,controller.signal).then(p => { if(!controller.signal.aborted) setPage(p); }).catch(() => { if(!controller.signal.aborted) setError(true); }).finally(() => { if(!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  },[petId]);
  // Acknowledge the guide only after the actual list (including its empty state) is visible.
  useEffect(()=>{
    if(!diaryIntro||!page||busy||introSeen.current)return;
    const frame=requestAnimationFrame(()=>{if(document.visibilityState!=='visible')return;introSeen.current=true;void live.tutorialSeen('DIARY_INTRO').then(r=>{if(!r.ok){introSeen.current=false;setError(true);}});});
    return()=>cancelAnimationFrame(frame);
  },[diaryIntro,page,busy,live.tutorialSeen]);
  async function load(more=false) {
    if(petId == null || lock.current) return; lock.current=true; setBusy(true); setError(false);
    try { const p=await getDiary(petId, more ? page?.nextBeforeId ?? undefined : undefined); if(active.current) setPage(old => more && old ? {...p,entries:[...old.entries,...p.entries.filter(e=>!old.entries.some(x=>x.id===e.id))]} : p); }
    catch { if(active.current) setError(true); } finally { lock.current=false; if(active.current) setBusy(false); }
  }
  async function ack(e:DiaryEntry) {
    if(petId == null || e.readAt || ackPending.current.has(e.id)) return; ackPending.current.add(e.id); setAckError(false);
    try { const saved=await readDiary(petId,e.id); if(active.current) { setEntry(old=>old?.id===saved.id?saved:old); setPage(old=>old?{...old,unreadCount:0,entries:old.entries.map(x=>x.id===saved.id?saved:x)}:old); } }
    catch { if(active.current) setAckError(true); } finally { ackPending.current.delete(e.id); }
  }
  // ACK only after the opened body is committed to the visible document.
  useEffect(() => { if(!entry || entry.readAt) return; const visible=()=>{if(document.visibilityState==='visible') void ack(entry);}; const frame=requestAnimationFrame(visible); document.addEventListener('visibilitychange',visible); return ()=>{cancelAnimationFrame(frame);document.removeEventListener('visibilitychange',visible);}; },[entry?.id,entry?.readAt]);
  return <section data-part="rewards-journal" style={{position:'absolute',inset:0,zIndex:10,background:C.paper,color:C.ink,display:'flex',flexDirection:'column'}}>
    <header style={{padding:'10px 16px',display:'flex',gap:12,alignItems:'center',flexWrap:'wrap'}}><h2 style={{fontSize:20,margin:0,flex:1}}>{t.diary}</h2><button style={button} onClick={onClose}>{t.back}</button></header>
    <div style={{overflowY:'auto',flex:1,minHeight:0,padding:'0 18px calc(18px + env(safe-area-inset-bottom))',overflowWrap:'anywhere'}}>
      {entry ? <article data-part="diary-body"><button style={button} onClick={()=>{setEntry(null);setAckError(false);}}>{t.close}</button><p><time dateTime={entry.writtenAt}>{formatInZone(entry.writtenAt,locale,{year:'numeric',month:'numeric',day:'numeric'})}</time></p><div lang={entry.language} style={{whiteSpace:'pre-wrap',lineHeight:1.8}}>{entry.body}</div>{ackError&&<p role="alert">{t.ackError} <button style={button} onClick={()=>void ack(entry)}>{t.retry}</button></p>}</article> : <>
        {hasBirth&&<article data-part="diary-birth-record" style={{padding:'18px 0',borderBottom:`1px solid ${C.lineHard}`}}><time dateTime={bornAt!} style={{fontSize:13,color:C.sub}}>{formatInZone(bornAt!,locale,{year:'numeric',month:'numeric',day:'numeric'})}</time><h3 style={{fontSize:18,lineHeight:1.5,margin:'8px 0'}}>{birthTitle}</h3><p style={{margin:0,lineHeight:1.7}}>{t.birthStory}</p></article>}
        <div role="status" style={{margin:'12px 0',fontSize:13}}>{page?.unreadCount?t.unread:''}</div>
        {busy&&<p role="status">{t.loading}</p>}{error&&<p role="alert">{t.error} <button style={button} disabled={busy} onClick={()=>void load()}>{t.retry}</button></p>}
        <div data-part="diary-list" data-tut-hl={diaryIntro?'1':undefined} style={{display:'flex',flexDirection:'column',minHeight:TAP_MIN,gap:10,animation:diaryIntro?'yBlink 1.2s ease-in-out infinite':undefined}}>{!busy&&(petId==null||page?.entries.length===0)&&<p>{page?.pending?t.pending:hasBirth?t.routineEmpty:t.empty}</p>}{page?.entries.map(e=><button key={e.id} style={{...button,textAlign:'left'}} onClick={()=>{metrics.attempt('journal_open');setAckError(false);setEntry(e);}}><time dateTime={e.writtenAt}>{formatInZone(e.writtenAt,locale,{year:'numeric',month:'numeric',day:'numeric'})}</time>{!e.readAt&&` · ${t.unread}`}<span lang={e.language} style={{display:'block',marginTop:6}}>{e.preview}</span></button>)}</div>
        {page?.nextBeforeId!=null&&<button style={{...button,marginTop:12}} disabled={busy} onClick={()=>void load(true)}>{t.more}</button>}
      </>}
    </div>
  </section>;
}


/** Room has no diary lifecycle: simply opening it must never fetch or acknowledge an entry. */
export function BackgroundRoom({onClose,sample=false}:{onClose:()=>void;sample?:boolean}) {
  const live=useLive(); const metrics=interactionMetrics(live,sample,'background'); const t=useRewardsCopy();
  const rewards=live.pet?.attendanceRewards;
  const current=rewards?.background.selectedKey ?? live.pet?.background ?? 'room';
  const [preview,setPreview]=useState(current);
  const [busy,setBusy]=useState(false); const [error,setError]=useState(false);
  const lock=useRef(false); const active=useRef(true);
  useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
  const owned=rewards?.background.owned ?? [];
  const choices=[{key:'room',url:''},...owned.filter(x=>x.key!=='room')];
  const available=choices.some(x=>x.key===preview);
  async function apply(){
    if(lock.current||!available||preview===current)return;
    metrics.attempt('background_apply');lock.current=true;setBusy(true);setError(false);
    try{await live.changeBackground(preview);metrics.result('background_apply','success');}catch{metrics.result('background_apply','failure');if(active.current)setError(true);}
    finally{lock.current=false;if(active.current)setBusy(false);}
  }
  return <section data-part="background-room" style={{position:'absolute',inset:0,zIndex:10,background:C.wallBg,color:C.ink,display:'flex',flexDirection:'column'}}>
    <header style={{padding:'12px 18px',display:'flex',gap:10,alignItems:'center',flexWrap:'wrap'}}><h2 style={{fontSize:20,margin:0,flex:1}}>{t.backgrounds}</h2><button style={button} onClick={onClose}>{t.back}</button></header>
    <div style={{overflowY:'auto',flex:1,minHeight:0,padding:'4px 18px 18px'}}>
      <div style={{display:'grid',gridTemplateColumns:'repeat(2,minmax(0,1fr))',gap:12}}>{choices.map((b,i)=><button key={b.key} data-background={b.key} aria-pressed={b.key===preview} disabled={busy} style={{...button,minWidth:0,padding:8,borderColor:b.key===preview?C.ink:C.lineHard,borderWidth:b.key===preview?2:1}} onClick={()=>{metrics.attempt('background_preview');setPreview(b.key);setError(false);}}>
        {b.url?<img src={b.url} alt="" style={{width:'100%',height:100,objectFit:'cover',display:'block',borderRadius:6,marginBottom:8}}/>:<span style={{display:'block',height:100,background:C.wallBg,borderRadius:6,marginBottom:8}}/>}
        <span>{i===0?t.room:`${t.roomMenu} ${i}`}</span><span style={{display:'block',marginTop:6,minHeight:20}}>{b.key===current?t.selected:b.key===preview?t.preview:''}</span>
      </button>)}</div>
      <button data-action="apply-background" style={{...button,marginTop:16}} disabled={busy||!available||preview===current} onClick={()=>void apply()}>{busy?t.applying:t.apply}</button>
      {error&&<p role="alert">{t.error}</p>}
    </div>
  </section>;
}
