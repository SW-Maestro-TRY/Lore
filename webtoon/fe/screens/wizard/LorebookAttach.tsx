"use client";
import {useEffect,useId,useRef,useState} from "react";
import {listLorebooks,savePrivateLorebook,extractLorebook,readLoreExtraction,type LoreBook,type LoreCatalog} from "../../lib/api";
import {attachedBooks,packBooks} from "../../lib/lorebooks";
import {useLang,useT} from "../../lib/i18n";
import {errText} from "../../ui/CreditShort";
import AuthModal from "@common/auth/AuthModal";
import {useAuth} from "@common/auth/useAuth";
import "./loreI18n";
import "./LorebookAttach.css";
const blank=():LoreBook=>({name:"",source:"manual",fixed_settings:"",progress_state:"",entries:[],confirmed:false});
export default function LorebookAttach({value,onChange,story,authenticated,onPendingChange}:{value?:LoreBook|null;onChange:(v:LoreBook|null)=>void;story:string;authenticated:boolean;onPendingChange?:(pending:boolean)=>void}) {
 const account=useAuth();const owner=account.user?.userId;const refreshSequence=useRef(0);const previousOwner=useRef<number|null|undefined>(undefined);
 const [saving,setSaving]=useState(false),[saved,setSaved]=useState(false);
 const t=useT();const {lang}=useLang();const helpId=useId();const [help,setHelp]=useState(false);const helpButton=useRef<HTMLButtonElement>(null);
 const [open,setOpen]=useState(false),[catalog,setCatalog]=useState<LoreCatalog|null>(null),[search,setSearch]=useState("");
 const books=attachedBooks(value);
 const [editing,setEditing]=useState(-1);const [insertAt,setInsertAt]=useState<number|null>(null);
 const [working,setWorking]=useState<LoreBook|null>(null),[undo,setUndo]=useState<{book:LoreBook;index:number}|null>(null);
 const [error,setError]=useState(""),[busy,setBusy]=useState(false),[auth,setAuth]=useState(false),[rights,setRights]=useState(false);
 const [extractId,setExtractId]=useState("");const active=useRef(true);const panel=useRef<HTMLDivElement>(null);const trigger=useRef<HTMLButtonElement>(null);
 useEffect(()=>{onPendingChange?.(busy || saving || (open && working!==null));},[busy,saving,open,working,onPendingChange]);
 useEffect(()=>()=>onPendingChange?.(false),[onPendingChange]);
 useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
 const refresh=async()=>{const seq=++refreshSequence.current;try{const result=await listLorebooks();if(active.current&&seq===refreshSequence.current)setCatalog(result);}catch(e){if(active.current&&seq===refreshSequence.current)setError(errText(e,t("설정집을 불러오지 못했어요")));}};
 useEffect(()=>{
  setCatalog(null);setSaved(false);void refresh();
  if(account.status==="loading")return;
  const identity=owner??null;
  const switched=previousOwner.current!==undefined&&previousOwner.current!==identity;
  previousOwner.current=identity;
  const kept=books.filter(b=>!b.library_id||b.library_owner===owner&&owner!==undefined);
  if(switched){setWorking(null);setUndo(null);setExtractId("");setBusy(false);setOpen(false);}
  if(kept.length!==books.length)onChange(packBooks(kept));
  return()=>{refreshSequence.current++;};
 },[authenticated,owner,account.status]);
 useEffect(()=>{if(open) panel.current?.querySelector<HTMLElement>("input,button")?.focus();},[open]);
 useEffect(()=>{
  if(!extractId)return;let alive=true,timer:ReturnType<typeof setTimeout>;const started=Date.now();
  const poll=async()=>{
   try {const result=await readLoreExtraction(extractId);if(!alive)return;
    if(result.status==="DONE"&&result.book){setWorking(result.book);setBusy(false);setExtractId("");void refresh();}
    else if(result.status==="ERROR")throw new Error(result.error||t("설정을 정리하지 못했어요"));
    else if(Date.now()-started>180000)throw new Error(t("설정 정리가 오래 걸리고 있어요. 잠시 후 다시 시도해 주세요."));
    else timer=setTimeout(poll,1200);
   }catch(e){if(alive){setError(errText(e,t("설정을 정리하지 못했어요")));setBusy(false);setExtractId("");void refresh();}}
  };void poll();return()=>{alive=false;clearTimeout(timer);};
 },[extractId]);
 const close=()=>{setOpen(false);trigger.current?.focus();};
 const edit=(index=-1)=>{setInsertAt(null);setEditing(index);setWorking(index<0?null:structuredClone(books[index]));setOpen(true);setError("");};
 const extract=async()=>{
  if(!authenticated){setAuth(true);return;}
  if(busy)return;setEditing(-1);setInsertAt(null);setBusy(true);setError("");
  try{const result=await extractLorebook(story,lang);if(active.current)setExtractId(result.id);}
  catch(e){if(active.current){setBusy(false);setError(errText(e,t("설정을 정리하지 못했어요")));}}
 };
 const patch=(p:Partial<LoreBook>)=>{setSaved(false);setWorking(w=>w?{...w,...p,confirmed:false}:w);};
 const remove=(index:number)=>{setUndo({book:books[index],index});onChange(packBooks(books.filter((_,i)=>i!==index)));setWorking(null);setEditing(-1);setOpen(false);};
 const saveMine=async()=>{
  if(!authenticated){setAuth(true);return;}
  if(!working||saving)return;
  setSaving(true);setError("");setSaved(false);const seq=refreshSequence.current;
  try{const book=await savePrivateLorebook({...working,confirmed:true});if(active.current&&seq===refreshSequence.current){setWorking(book);setSaved(true);void refresh();}}
  catch(e){if(active.current&&seq===refreshSequence.current)setError(errText(e,t("설정집을 저장하지 못했어요")));}
  finally{if(active.current)setSaving(false);}
 };
 const attach=()=>{
  if(!working)return;
  const book={...working,name:working.name.trim()||t("내 설정집"),confirmed:true};
  const next=[...books];if(editing<0)next.splice(insertAt??next.length,0,book);else next[editing]=book;
  const packed=packBooks(next)!;
  if(next.length>24||packed.fixed_settings.length+packed.progress_state.length+packed.entries.reduce((n,e)=>n+e.content.length,0)>16000){setError(t("설정집은 합쳐서 16,000자까지예요."));return;}
  onChange(packed);setUndo(null);setWorking(null);setEditing(-1);close();
 };
 return <section className="wt-lore" aria-label={t("설정집")}>
  <div className="wt-lore-attachline">
   <button ref={trigger} type="button" className="btn btn-w" disabled={busy} onClick={()=>edit()}>+ {t("설정집")}</button>
   <button ref={helpButton} type="button" className="wt-lore-help-button" aria-label={t("설정집이란?")} aria-expanded={help} aria-controls={helpId} onClick={()=>setHelp(v=>!v)}><span aria-hidden="true">?</span></button>

  </div>
  {!!books.length&&<ul className="wt-lore-attached-list">{books.map((book,index)=><li className="wt-lore-attached" key={index}>
   <button type="button" disabled={busy} onClick={()=>edit(index)}><b>{book.name||t("내 설정집")}</b>{!book.confirmed&&<span>{t("설정을 다시 확인해 주세요")}</span>}</button>
   <button type="button" className="wt-lore-remove" aria-label={t("{name} 설정집 빼기",{name:book.name})} disabled={busy} onClick={()=>remove(index)}>{t("빼기")}</button>
  </li>)}</ul>}
  {undo&&<button type="button" className="btn-ghost" disabled={busy} onClick={()=>{setWorking({...undo.book,confirmed:false});setInsertAt(undo.index);setEditing(-1);setOpen(true);setUndo(null);}}>{t("방금 뺀 설정집 다시 붙이기")}</button>}
  {help&&<div id={helpId} className="wt-lore-help" role="region" aria-label={t("설정집이란?")} onKeyDown={e=>{if(e.key==="Escape"){e.stopPropagation();setHelp(false);helpButton.current?.focus();}}}>
   <div className="wt-lore-panelhead"><h3>{t("설정집이란?")}</h3><button type="button" className="btn-ghost" aria-label={t("설정집 설명 닫기")} onClick={()=>{setHelp(false);helpButton.current?.focus();}}>{t("닫기")}</button></div>
   <p>{t("로어북이라고도 해요. 등장인물과 세계관, 지금까지의 이야기를 기억해 다음 화에도 이어 주는 설정집이에요.")}</p>
   <dl><div><dt>{t("고정 설정")}</dt><dd>{t("계속 지킬 인물·세계의 규칙. 예: 주인공은 높은 곳을 무서워해요.")}</dd></div><div><dt>{t("진행 상태")}</dt><dd>{t("이야기에 따라 달라지는 현재 상황. 예: 친구에게 비밀을 털어놓았어요.")}</dd></div></dl>
  </div>}
  {open&&<div className="wt-lore-panel" ref={panel}>
   <div className="wt-lore-panelhead"><div><h3>{working?t("설정을 확인해 주세요"):t("이야기에 설정집 붙이기")}</h3></div>
    <button type="button" className="btn-ghost" aria-label={t("설정집 닫기")} disabled={busy||saving} onClick={close}>{t("닫기")} ×</button></div>
   {busy&&<p role="status">{t("설정 추출 중…")}</p>}
   {!working?<>
    <div className="wt-lore-extract"><b>{t("지금 적은 글에서 설정 찾기")}</b>

     {authenticated?<label className="wt-lore-rights"><input type="checkbox" checked={rights} onChange={e=>setRights(e.target.checked)}/>{t("입력한 글을 사용할 권리가 있어요.")}</label>:null}
     <button type="button" className="btn btn-w" disabled={busy||(authenticated&&(!rights||story.trim().length<30||catalog?.extract_left===0))} onClick={()=>void extract()}>{authenticated?t("이 글에서 설정 정리"):t("로그인")}</button>
     <span className="muted">{story.trim().length<30?t("30자 이상"):catalog&&authenticated?t("오늘 {n}회 남음 · 무료",{n:catalog.extract_left}):""}</span>
    </div>
    <label className="wt-lore-search">{t("설정집 찾기")}<input className="field" value={search} placeholder={t("설정집 찾기")} onChange={e=>setSearch(e.target.value)}/></label>

    <h4>{t("기본 설정집")}</h4>
    <div className="wt-lore-catalog">
     {catalog?.books.filter(b=>(b.name+" "+b.fixed_settings).includes(search)).map(b=><button type="button" key={b.id||b.name} disabled={busy||books.some(v=>v.id&&v.id===b.id)} onClick={()=>{setInsertAt(null);setEditing(-1);setWorking(structuredClone(b));}}><b>{t(b.name)}</b><span>{b.fixed_settings}</span><small>{t("설정 {n}개",{n:b.entries.length})} · {t("내용 보기")}</small></button>)}
    </div>
    {authenticated&&<><h4>{t("내 설정집")}</h4><div className="wt-lore-catalog wt-lore-personal">
     {catalog?.personal_books?.filter(b=>(b.name+" "+b.fixed_settings).includes(search)).map(b=><button type="button" key={b.library_id} disabled={busy} onClick={()=>{setSaved(false);setInsertAt(null);setEditing(-1);setWorking(structuredClone(b));}}><b>{b.name}</b><span>{b.fixed_settings}</span><small>{t("내용 보기")}</small></button>)}
    </div>{catalog&&!catalog.personal_books?.length&&<p className="muted">{t("저장한 설정집이 없어요")}</p>}</>}
    {!catalog&&!error&&<p role="status">{t("설정집을 불러오고 있어요")}</p>}
    {catalog&&!catalog.books.some(b=>(b.name+" "+b.fixed_settings).includes(search))&&<p>{t("검색 결과 없음")}</p>}
    <button type="button" className="btn-ghost" disabled={busy} onClick={()=>{setInsertAt(null);setEditing(-1);setWorking(blank());}}>{t("빈 설정집에 직접 적기")}</button>
   </>:<fieldset className="wt-lore-editor" disabled={saving}>
    <label>{t("설정집 이름")}<input className="field" maxLength={100} value={working.name} onChange={e=>patch({name:e.target.value})}/></label>
    <label>{t("고정 설정")}<textarea className="field" rows={5} maxLength={8000} value={working.fixed_settings} onChange={e=>patch({fixed_settings:e.target.value})}/></label>
    <label>{t("시작 시점 · 선택")}<textarea className="field" rows={3} maxLength={8000} value={working.progress_state} onChange={e=>patch({progress_state:e.target.value})}/></label>
    {working.source==="extracted"&&<p className="wt-lore-reviewnote">{t("AI 초안")}</p>}
    {!!working.entries.length&&<details className="wt-lore-entries"><summary>{t("세부 설정 {n}개 확인·수정",{n:working.entries.length})}</summary>
     {working.entries.map((e,i)=><details key={i}><summary>{e.title}</summary>
      <label><input type="checkbox" checked={e.enabled} onChange={ev=>patch({entries:working.entries.map((v,j)=>j===i?{...v,enabled:ev.target.checked}:v)})}/>{t("이 설정 사용")}</label>
      <textarea className="field" aria-label={e.title} rows={4} maxLength={2000} value={e.content} onChange={ev=>patch({entries:working.entries.map((v,j)=>j===i?{...v,content:ev.target.value}:v)})}/>
      <label>{t("참고 키워드")}<input className="field" value={e.keys.join(", ")} onChange={ev=>patch({entries:working.entries.map((v,j)=>j===i?{...v,keys:ev.target.value.split(",").map(k=>k.trim()).filter(Boolean).slice(0,12)}:v)})}/></label>
      <label><input type="checkbox" checked={e.always} onChange={ev=>patch({entries:working.entries.map((v,j)=>j===i?{...v,always:ev.target.checked}:v)})}/>{t("항상 참고하기")}</label>
      {e.evidence&&<blockquote><span>{t("원문 근거")}</span>{e.evidence}</blockquote>}
     </details>)}
    </details>}

    <div className="wt-lore-private-save"><button type="button" className="btn btn-w" disabled={busy||saving||!working.name.trim()} onClick={()=>void saveMine()}>{saving?t("저장 중…"):t("내 설정집에 저장")}</button>{saved&&<span role="status">{t("저장했어요")}</span>}</div>
    <div className="wt-lore-actions"><button type="button" className="btn btn-w" onClick={()=>{setWorking(null);setEditing(-1);setInsertAt(null);}}>{t("목록으로")}</button><button type="button" className="btn btn-p" disabled={busy||!working.fixed_settings.trim()&&!working.entries.some(e=>e.enabled&&e.content.trim())} onClick={attach}>{t("확인하고 붙이기")}</button></div>
   </fieldset>}
   {error&&<p role="alert">{error} {!catalog&&<button type="button" onClick={()=>{setError("");void refresh();}}>{t("다시 시도")}</button>}</p>}
  </div>}
  {auth&&<AuthModal open onClose={()=>setAuth(false)}/>}
 </section>;
}
