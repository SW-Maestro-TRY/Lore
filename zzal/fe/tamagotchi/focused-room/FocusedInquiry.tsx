'use client';
import {interactionMetrics} from './interactionMetrics';
import {useCallback,useEffect,useRef,useState} from 'react';
import {useAuth} from '@common/auth/useAuth';
import {ApiError} from '@common/api/client';
import type {ZzalLocale} from '../../lib/locale';
import {randomUuid} from '../../lib/randomUuid';
import {inquirySeen,listInquiries,submitInquiry,type InquiryInput,type InquiryItem} from '../../lib/inquiry';
import {NewDot} from './newBadges';
import './inquiry.css';
const EMAIL='jk08097@naver.com';
function copy(locale:ZzalLocale){return locale==='en'?{title:'Contact us',body:'Your inquiry',email:'Email',send:'Send inquiry',retry:'Check again',busy:'Sending…',close:'Close',saved:'Your inquiry has been received.',error:'Could not confirm your inquiry. Your message is still here. Please check again.',invalid:'Could not submit your inquiry. Check your message and try again.',me:'You',team:'Lore team',more:'Ask a follow-up',morePlaceholder:'Write anything else you want to ask',sendMore:'Send',notice:'When we reply, a dot appears on the menu.',loading:'Loading…',loadError:'Could not load your inquiries.',reload:'Try again'}:locale==='ja'?{title:'お問い合わせ',body:'お問い合わせ内容',email:'メール',send:'問い合わせを送る',retry:'再確認',busy:'送信中…',close:'閉じる',saved:'お問い合わせを受け付けました。',error:'受付を確認できませんでした。内容は残っています。同じ内容でもう一度確認してください。',invalid:'送信できませんでした。内容を確認してもう一度お試しください。',me:'あなた',team:'Lore運営チーム',more:'追加で質問する',morePlaceholder:'気になることを書いてください',sendMore:'送信',notice:'返信が届くとメニューに点でお知らせします',loading:'読み込み中…',loadError:'お問い合わせ履歴を読み込めませんでした。',reload:'もう一度'}:{title:'문의하기',body:'문의 내용',email:'이메일',send:'문의 남기기',retry:'다시 확인',busy:'보내는 중…',close:'닫기',saved:'문의를 접수했어요.',error:'접수를 확인하지 못했어요. 적은 내용은 남아 있어요. 같은 내용으로 다시 확인해 주세요.',invalid:'문의를 보내지 못했어요. 내용을 확인하고 다시 시도해 주세요.',me:'나',team:'로어 운영팀',more:'더 물어보기',morePlaceholder:'더 궁금한 점을 적어 주세요',sendMore:'보내기',notice:'답장이 오면 메뉴에 점으로 알려드려요',loading:'불러오는 중…',loadError:'문의 기록을 불러오지 못했어요.',reload:'다시 불러오기'};}
function when(iso:string,locale:ZzalLocale){const d=new Date(iso);return Number.isFinite(d.getTime())?d.toLocaleString(locale==='ko'?'ko-KR':locale==='ja'?'ja-JP':'en-US',{month:'short',day:'numeric',hour:'numeric',minute:'2-digit'}):'';}

/**
 * 문의 화면 — 대화 기록형(목업 34-2, 2026-10-28 상훈님 결정).
 *
 * ★ 답장은 캐릭터가 아니라 「로어 운영팀」 시스템 문구다 — 캐릭터 이름·얼굴·말풍선을 쓰지 않는다.
 * ★ "새 답장" 같은 글자 라벨은 없다. 아직 안 본 답장은 머리줄 끝의 작은 점 하나로만 표시하고,
 *   시트를 열어 보이면 seen 을 보내고 상태를 다시 읽어 메뉴의 점을 지운다(이번에 연 동안은 점을 남겨 어느 것이 새것인지 보인다).
 * ★ 문의가 없으면 지금까지와 같은 새 문의 쓰기 화면. 있으면 맨 아래 「더 물어보기」가 마지막 문의에 이어 쓴다(parentId).
 */
export default function FocusedInquiry({open,onClose,onSeen,petId,locale,metrics=interactionMetrics({pet:null},false,'inquiry')}:{metrics?:ReturnType<typeof interactionMetrics>;open:boolean;onClose:()=>void;onSeen?:()=>void;petId:number|null;locale:ZzalLocale}){
 useEffect(()=>{if(open)metrics.shown('inquiry')},[open]);
 const auth=useAuth(),scope=`${auth.user?.userId??''}:${petId??''}`,currentScope=useRef(scope);currentScope.current=scope;
 const t=copy(locale),root=useRef<HTMLDivElement>(null),mounted=useRef(true),lock=useRef(false),pending=useRef<InquiryInput|null>(null),controller=useRef<AbortController|null>(null);
 const [body,setBody]=useState(''),[busy,setBusy]=useState(false),[uncertain,setUncertain]=useState(false),[error,setError]=useState<string|null>(null),[saved,setSaved]=useState(false);
 const [items,setItems]=useState<InquiryItem[]|null>(null),[loadFailed,setLoadFailed]=useState(false),[fresh,setFresh]=useState<Set<number>>(()=>new Set());
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;controller.current?.abort()}},[]);
 useEffect(()=>{controller.current?.abort();pending.current=null;lock.current=false;setBody('');setBusy(false);setUncertain(false);setError(null);setSaved(false);setItems(null);setFresh(new Set())},[scope]);
 const load=useCallback(async()=>{
  if(!auth.user)return;const identity=scope;setLoadFailed(false);
  try{const list=await listInquiries();if(!mounted.current||currentScope.current!==identity)return;setItems(list);
   const unseen=list.filter(item=>item.reply&&item.replyUnseen).map(item=>item.id);
   if(unseen.length){setFresh(prev=>new Set([...prev,...unseen]));
    // 보였으니 봤다고 남긴다. 하나라도 못 남기면 점이 남을 뿐이다(다음에 열면 또 보낸다).
    const results=await Promise.allSettled(unseen.map(id=>inquirySeen(id)));if(results.some(r=>r.status==='fulfilled'))onSeen?.();}
  }catch{if(mounted.current&&currentScope.current===identity){setLoadFailed(true);}}
 },[auth.user,scope,onSeen]);
 useEffect(()=>{if(open){setFresh(new Set());void load();}},[open,scope]);
 useEffect(()=>{
  if(!open)return;const dialog=root.current,host=dialog?.closest<HTMLElement>('[data-focused-room]'),previous=document.activeElement as HTMLElement|null;
  const others=[...host?.children??[]].filter(node=>node!==dialog) as HTMLElement[],prior=others.map(node=>node.inert);others.forEach(node=>{node.inert=true});dialog?.querySelector<HTMLElement>('header button')?.focus({preventScroll:true});
  return()=>{others.forEach((node,i)=>{node.inert=prior[i]});requestAnimationFrame(()=>previous?.isConnected&&previous.focus({preventScroll:true}))};
 },[open]);
 const thread=items??[],parentId=thread.length?thread[thread.length-1].id:null,followUp=thread.length>0;
 const submit=async()=>{
  if(lock.current||(!followUp&&saved)||!body.trim()||!auth.user)return;
  metrics.attempt('inquiry_submit');lock.current=true;setBusy(true);setError(null);const identity=scope;let payload=pending.current;
  const abort=new AbortController();controller.current=abort;const timer=setTimeout(()=>abort.abort(),15000);
  try{
   payload??={requestId:randomUuid(),body:body.trim(),locale,petId,parentId};pending.current=payload;
   await submitInquiry(payload,abort.signal);metrics.result('inquiry_submit','success');
   if(mounted.current&&currentScope.current===identity&&pending.current===payload){setUncertain(false);pending.current=null;
    if(followUp){setBody('');void load();}else{setSaved(true);setBody('');void load();}}
  }catch(e){metrics.result('inquiry_submit',e instanceof ApiError&&e.status>=400&&e.status<500?'failure':'uncertain');
   if(mounted.current&&currentScope.current===identity&&pending.current===payload){const unknown=!(e instanceof ApiError&&e.status>=400&&e.status<500);setUncertain(unknown);setError(unknown?t.error:t.invalid);}
  }finally{clearTimeout(timer);if(mounted.current&&currentScope.current===identity&&controller.current===abort){lock.current=false;setBusy(false);controller.current=null;}}
 };
 if(!open)return null;
 const notice=<p className="fr-inquiry-notice" data-fr-inquiry-notice><span aria-hidden="true"/>{t.notice}</p>;
 const textarea=(id:string,placeholder?:string)=><textarea id={id} data-fr-inquiry-body maxLength={4000} value={body} placeholder={placeholder} disabled={busy} readOnly={uncertain} onChange={event=>{pending.current=null;setBody(event.target.value);setError(null)}}/>;
 return <div ref={root} className="fr-sheet fr-reading-sheet fr-inquiry" data-fr-inquiry role="dialog" aria-modal="true" aria-label={t.title} onKeyDown={event=>{if(event.key==='Escape'){event.stopPropagation();onClose()}if(event.key==='Tab'){const controls=[...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],textarea:not(:disabled)')];if(event.shiftKey&&document.activeElement===controls[0]){event.preventDefault();controls.at(-1)?.focus()}else if(!event.shiftKey&&document.activeElement===controls.at(-1)){event.preventDefault();controls[0]?.focus()}}}}>
  <header><h2>{t.title}</h2><button type="button" onClick={onClose}>{t.close}</button></header>
  <div className="fr-sheet-body">
   {items===null&&!loadFailed&&auth.user&&<p className="fr-inquiry-loading" role="status">{t.loading}</p>}
   {loadFailed&&<p className="fr-panel-error" role="alert">{t.loadError} <button type="button" className="fr-inquiry-reload" onClick={()=>void load()}>{t.reload}</button></p>}
   {followUp?<>
    <ol className="fr-inquiry-thread" data-fr-inquiry-thread>{thread.map(item=><li key={item.id} data-fr-inquiry-item={item.id}>
     <p className="fr-inquiry-meta">{t.me} · {when(item.createdAt,locale)}</p>
     <p className="fr-inquiry-mine" data-fr-inquiry-mine>{item.body}</p>
     {item.reply&&<>
      <p className="fr-inquiry-meta fr-inquiry-meta--team" data-fr-inquiry-team><svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 3h10v7H7l-3 3v-3H3z"/></svg>{t.team} · {when(item.reply.repliedAt,locale)}{fresh.has(item.id)&&<NewDot place="inline" count={1} locale={locale}/>}</p>
      <p className="fr-inquiry-reply" data-fr-inquiry-reply>{item.reply.text}</p>
     </>}
    </li>)}</ol>
    <form className="fr-inquiry-more" onSubmit={event=>{event.preventDefault();void submit()}}>
     <label htmlFor="fr-inquiry-more">{t.more}</label>{textarea('fr-inquiry-more',t.morePlaceholder)}
     {error&&<p className="fr-panel-error" role="alert">{error}</p>}
     <button type="submit" data-fr-inquiry-submit disabled={busy||!body.trim()}>{busy?t.busy:uncertain?t.retry:t.sendMore}</button>
    </form>
    {notice}
   </>:items!==null||loadFailed||!auth.user?<>
    {saved?<p data-fr-inquiry-saved role="status">{t.saved}</p>:<form onSubmit={event=>{event.preventDefault();void submit()}}>
     <label htmlFor="fr-inquiry-body">{t.body}</label>{textarea('fr-inquiry-body')}
     {error&&<p className="fr-panel-error" role="alert">{error}</p>}
     <button type="submit" data-fr-inquiry-submit disabled={busy||!body.trim()}>{busy?t.busy:uncertain?t.retry:t.send}</button>
    </form>}
    {notice}
   </>:null}
   <p className="fr-inquiry-email">{t.email} <a href={`mailto:${EMAIL}`}>{EMAIL}</a></p>
  </div>
 </div>;
}
