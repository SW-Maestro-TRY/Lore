'use client';
import {interactionMetrics} from './interactionMetrics';
import {useEffect,useRef,useState} from 'react';
import {useAuth} from '@common/auth/useAuth';
import {ApiError} from '@common/api/client';
import type {ZzalLocale} from '../../lib/locale';
import {randomUuid} from '../../lib/randomUuid';
import {submitInquiry,type InquiryInput,type InquiryReceipt} from '../../lib/inquiry';
import './inquiry.css';
const EMAIL='jk08097@naver.com';
function copy(locale:ZzalLocale){return locale==='en'?{title:'Contact us',body:'Your inquiry',email:'Email',send:'Send inquiry',retry:'Check again',busy:'Sending…',close:'Close',saved:'Your inquiry has been received.',error:'Could not confirm your inquiry. Your message is still here. Please check again.',invalid:'Could not submit your inquiry. Check your message and try again.'}:locale==='ja'?{title:'お問い合わせ',body:'お問い合わせ内容',email:'メール',send:'問い合わせを送る',retry:'再確認',busy:'送信中…',close:'閉じる',saved:'お問い合わせを受け付けました。',error:'受付を確認できませんでした。内容は残っています。同じ内容でもう一度確認してください。',invalid:'送信できませんでした。内容を確認してもう一度お試しください。'}:{title:'문의하기',body:'문의 내용',email:'이메일',send:'문의 남기기',retry:'다시 확인',busy:'보내는 중…',close:'닫기',saved:'문의를 접수했어요.',error:'접수를 확인하지 못했어요. 적은 내용은 남아 있어요. 같은 내용으로 다시 확인해 주세요.',invalid:'문의를 보내지 못했어요. 내용을 확인하고 다시 시도해 주세요.'};}
export default function FocusedInquiry({open,onClose,petId,locale,metrics=interactionMetrics({pet:null},false,'inquiry')}:{metrics?:ReturnType<typeof interactionMetrics>;open:boolean;onClose:()=>void;petId:number|null;locale:ZzalLocale}){
 useEffect(()=>{if(open)metrics.shown('inquiry')},[open]);
 const auth=useAuth(),scope=`${auth.user?.userId??''}:${petId??''}`,currentScope=useRef(scope);currentScope.current=scope;
 const t=copy(locale),root=useRef<HTMLDivElement>(null),mounted=useRef(true),lock=useRef(false),pending=useRef<InquiryInput|null>(null),controller=useRef<AbortController|null>(null);
 const [body,setBody]=useState(''),[busy,setBusy]=useState(false),[uncertain,setUncertain]=useState(false),[error,setError]=useState<string|null>(null),[receipt,setReceipt]=useState<InquiryReceipt|null>(null);
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;controller.current?.abort()}},[]);
 useEffect(()=>{controller.current?.abort();pending.current=null;lock.current=false;setBody('');setBusy(false);setUncertain(false);setError(null);setReceipt(null)},[scope]);
 useEffect(()=>{
  if(!open)return;const dialog=root.current,host=dialog?.closest<HTMLElement>('[data-focused-room]'),previous=document.activeElement as HTMLElement|null;
  const others=[...host?.children??[]].filter(node=>node!==dialog) as HTMLElement[],prior=others.map(node=>node.inert);others.forEach(node=>{node.inert=true});dialog?.querySelector<HTMLElement>('textarea,button')?.focus({preventScroll:true});
  return()=>{others.forEach((node,i)=>{node.inert=prior[i]});requestAnimationFrame(()=>previous?.isConnected&&previous.focus({preventScroll:true}))};
 },[open]);
 const submit=async()=>{
  if(lock.current||receipt||!body.trim()||!auth.user)return;
  metrics.attempt('inquiry_submit');lock.current=true;setBusy(true);setError(null);const identity=scope;let payload=pending.current;
  const abort=new AbortController();controller.current=abort;const timer=setTimeout(()=>abort.abort(),15000);
  try{
   payload??={requestId:randomUuid(),body:body.trim(),locale,petId};pending.current=payload;
   const saved=await submitInquiry(payload,abort.signal);metrics.result('inquiry_submit','success');
   if(mounted.current&&currentScope.current===identity&&pending.current===payload){setReceipt(saved);setUncertain(false);}
  }catch(e){metrics.result('inquiry_submit',e instanceof ApiError&&e.status>=400&&e.status<500?'failure':'uncertain');
   if(mounted.current&&currentScope.current===identity&&pending.current===payload){const unknown=!(e instanceof ApiError&&e.status>=400&&e.status<500);setUncertain(unknown);setError(unknown?t.error:t.invalid);}
  }finally{clearTimeout(timer);if(mounted.current&&currentScope.current===identity&&controller.current===abort){lock.current=false;setBusy(false);controller.current=null;}}
 };
 if(!open)return null;
 return <div ref={root} className="fr-sheet fr-reading-sheet fr-inquiry" data-fr-inquiry role="dialog" aria-modal="true" aria-label={t.title} onKeyDown={event=>{if(event.key==='Escape'){event.stopPropagation();onClose()}if(event.key==='Tab'){const controls=[...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],textarea:not(:disabled)')];if(event.shiftKey&&document.activeElement===controls[0]){event.preventDefault();controls.at(-1)?.focus()}else if(!event.shiftKey&&document.activeElement===controls.at(-1)){event.preventDefault();controls[0]?.focus()}}}}>
  <header><h2>{t.title}</h2><button type="button" onClick={onClose}>{t.close}</button></header>
  <div className="fr-sheet-body">
   <p className="fr-inquiry-email">{t.email} <a href={`mailto:${EMAIL}`}>{EMAIL}</a></p>
   {receipt?<p data-fr-inquiry-saved role="status">{t.saved}</p>:<form onSubmit={event=>{event.preventDefault();void submit()}}>
    <label htmlFor="fr-inquiry-body">{t.body}</label><textarea id="fr-inquiry-body" data-fr-inquiry-body maxLength={4000} value={body} disabled={busy} readOnly={uncertain} onChange={event=>{pending.current=null;setBody(event.target.value);setError(null)}}/>
    {error&&<p className="fr-panel-error" role="alert">{error}</p>}
    <button type="submit" data-fr-inquiry-submit disabled={busy||!body.trim()}>{busy?t.busy:uncertain?t.retry:t.send}</button>
   </form>}
  </div>
 </div>;
}
