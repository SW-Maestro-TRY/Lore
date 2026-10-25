"use client";
import {useEffect,useId,useRef,useState} from "react";
import {listLorebooks,savePrivateLorebook,extractLorebook,readLoreExtraction,type LoreBook,type LoreCatalog} from "../../lib/api";
import {attachedBooks,packBooks} from "../../lib/lorebooks";
import {useLang,useT} from "../../lib/i18n";
import {IconClose,IconMinus,IconPlus} from "../../ui/Icons";
import {errText} from "../../ui/CreditShort";
import AuthModal from "@common/auth/AuthModal";
import {useAuth} from "@common/auth/useAuth";
import "./loreI18n";
import "./LorebookAttach.css";
const AI_MAX=20000;
/* 설정집은 2층 로어북이다 — 항목(키워드가 나오면 읽고, 「항상 참고」는 매번 읽는다) + 진행 상태(화가 끝날 때마다 AI가
   정리한다). 사람은 항목만 쓴다. 옛 설정집의 「고정 설정」 글은 고칠 때 「항상 참고」 항목 하나로 옮긴다. 「시작 시점」은
   받지 않는다(1화는 진행 상태 없이 시작 — orv-experiment 와 같다). 옛 설정집에 이미 적힌 것만 보이고 지울 수 있다. */
const emptyEntry=()=>({kind:"setting",title:"",content:"",keys:[] as string[],enabled:true,always:false});
const blank=():LoreBook=>({name:"",source:"manual",fixed_settings:"",progress_state:"",entries:[emptyEntry()],confirmed:false});
// 빈 항목은 버린다. 항목 이름은 하네스가 늘 키워드로 치니(userbook.select) 키워드 칸에 옮겨 적지 않는다.
const tidy=(b:LoreBook):LoreBook=>({...b,entries:b.entries.filter(e=>e.title.trim()||e.content.trim()).map(e=>({...e,title:e.title.trim()}))});
const asEntries=(b:LoreBook):LoreBook=>!b.fixed_settings.trim()?b:{...b,fixed_settings:"",
  entries:[{kind:"world",title:b.name.trim()||"기본 설정",content:b.fixed_settings.trim(),keys:[],enabled:true,always:true},...b.entries]};
export default function LorebookAttach({value,onChange,story,authenticated,onPendingChange}:{value?:LoreBook|null;onChange:(v:LoreBook|null)=>void;story:string;authenticated:boolean;onPendingChange?:(pending:boolean)=>void}) {
 const account=useAuth();const owner=account.user?.userId;const refreshSequence=useRef(0);const previousOwner=useRef<number|null|undefined>(undefined);
 const [saving,setSaving]=useState(false),[saved,setSaved]=useState(false);
 const t=useT();const {lang}=useLang();const helpId=useId();const [help,setHelp]=useState(false);const helpButton=useRef<HTMLButtonElement>(null);
 const [open,setOpen]=useState(false),[catalog,setCatalog]=useState<LoreCatalog|null>(null),[search,setSearch]=useState("");
 const books=attachedBooks(value);
 const [editMode,setEditMode]=useState(false);const beforeEdit=useRef<LoreBook|null>(null);
 const dialog=useRef<HTMLDialogElement>(null);
 const [editing,setEditing]=useState(-1);const [insertAt,setInsertAt]=useState<number|null>(null);
 const [working,setWorking]=useState<LoreBook|null>(null),[undo,setUndo]=useState<{book:LoreBook;index:number}|null>(null);
 const [error,setError]=useState(""),[busy,setBusy]=useState(false),[auth,setAuth]=useState(false),[rights,setRights]=useState(false);
 const [extractId,setExtractId]=useState("");
 /* AI로 만들기 — 긴 이야기를 붙여 넣거나 글 파일을 올리면 AI가 설정집 초안을 만든다(서버 한도 20,000자) */
 const [aiMode,setAiMode]=useState(false),[aiText,setAiText]=useState(""),[aiNote,setAiNote]=useState("");const aiFile=useRef<HTMLInputElement>(null);const active=useRef(true);const panel=useRef<HTMLDivElement>(null);const trigger=useRef<HTMLButtonElement>(null);
 useEffect(()=>{onPendingChange?.(busy || saving || (open && working!==null && editMode));},[busy,saving,open,working,editMode,onPendingChange]);
 useEffect(()=>()=>onPendingChange?.(false),[onPendingChange]);
 useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
 const refresh=async()=>{const seq=++refreshSequence.current;try{const result=await listLorebooks();if(active.current&&seq===refreshSequence.current)setCatalog(result);}catch(e){if(active.current&&seq===refreshSequence.current)setError(errText(e,t("설정집을 불러오지 못했어요")));}};
 useEffect(()=>{
  setCatalog(null);setSaved(false);void refresh();
  if(account.status==="loading")return;
  const identity=owner??null;
  const switched=previousOwner.current!==undefined&&previousOwner.current!==identity;
  const signingIn=previousOwner.current===null&&identity!==null;
  previousOwner.current=identity;
  const kept=books.filter(b=>!b.library_id||b.library_owner===owner&&owner!==undefined);
  if(switched && !(signingIn && working && !working.library_id)){setWorking(null);setUndo(null);setExtractId("");setBusy(false);setOpen(false);}
  if(kept.length!==books.length)onChange(packBooks(kept));
  return()=>{refreshSequence.current++;};
 },[authenticated,owner,account.status]);
 useEffect(()=>{if(open&&!auth){dialog.current?.showModal();panel.current?.querySelector<HTMLElement>("input,button")?.focus();}else dialog.current?.close();},[open,auth]);
 useEffect(()=>{dialog.current?.scrollTo(0,0);},[working?.id,working?.library_id,!!working,editMode]);
 useEffect(()=>{
  if(!extractId)return;let alive=true,timer:ReturnType<typeof setTimeout>;const started=Date.now();
  const poll=async()=>{
   try {const result=await readLoreExtraction(extractId);if(!alive)return;
    if(result.status==="DONE"&&result.book){beforeEdit.current=null;setWorking(asEntries(result.book));setEditMode(true);setAiMode(false);setBusy(false);setExtractId("");void refresh();}
    else if(result.status==="ERROR")throw new Error(result.error||t("설정을 정리하지 못했어요"));
    else if(Date.now()-started>180000)throw new Error(t("설정 정리가 오래 걸리고 있어요. 잠시 후 다시 시도해 주세요."));
    else timer=setTimeout(poll,1200);
   }catch(e){if(alive){setError(errText(e,t("설정을 정리하지 못했어요")));setBusy(false);setExtractId("");void refresh();}}
  };void poll();return()=>{alive=false;clearTimeout(timer);};
 },[extractId]);
 const close=()=>{setOpen(false);trigger.current?.focus();};
 const edit=(index=-1)=>{setEditMode(false);setInsertAt(null);setEditing(index);setWorking(index<0?null:structuredClone(books[index]));setOpen(true);setError("");};
 const extract=async()=>{
  if(!authenticated){setAuth(true);return;}
  if(busy)return;setEditing(-1);setInsertAt(null);setBusy(true);setError("");
  try{const result=await extractLorebook(aiText,lang);if(active.current)setExtractId(result.id);}
  catch(e){if(active.current){setBusy(false);setError(errText(e,t("설정을 정리하지 못했어요")));}}
 };
 const startNew=()=>{setInsertAt(null);setEditing(-1);setWorking(blank());beforeEdit.current=null;setEditMode(true);setAiMode(false);setError("");};
 const readFile=async(file?:File|null)=>{
  if(!file)return;setAiNote("");
  if(!/\.(txt|md)$/i.test(file.name)&&!file.type.startsWith("text/")){setAiNote(t("글 파일(.txt)만 올릴 수 있어요"));return;}
  const text=(await file.text()).trim();
  if(text.length>AI_MAX){setAiText(text.slice(0,AI_MAX));setAiNote(t("앞 {n}자만 넣었어요",{n:AI_MAX.toLocaleString()}));}else setAiText(text);
 };
 const patch=(p:Partial<LoreBook>)=>{setSaved(false);setWorking(w=>w?{...w,...p,confirmed:false}:w);};
 const remove=(index:number)=>{setUndo({book:books[index],index});onChange(packBooks(books.filter((_,i)=>i!==index)));setWorking(null);setEditing(-1);setOpen(false);};
 const saveMine=async()=>{
  if(!authenticated){setAuth(true);return;}
  if(!working||saving)return;
  setSaving(true);setError("");setSaved(false);const seq=refreshSequence.current;
  try{const book=await savePrivateLorebook({...tidy(working),confirmed:true});if(active.current&&seq===refreshSequence.current){setWorking(book);setEditMode(false);setSaved(true);void refresh();}}
  catch(e){if(active.current&&seq===refreshSequence.current)setError(errText(e,t("설정집을 저장하지 못했어요")));}
  finally{if(active.current)setSaving(false);}
 };
 const attach=async()=>{
  if(!working||saving)return;
  let book={...tidy(working),name:working.name.trim()||t("내 설정집"),confirmed:true};
  // 쓰거나 고친 설정집은 붙이면서 내 설정집에도 저장한다(로그인했을 때). 저장이 안 되면 붙이지 않고 알린다.
  if(authenticated&&editMode){
   setSaving(true);setError("");
   try{book={...await savePrivateLorebook(book),confirmed:true};void refresh();}
   catch(e){if(active.current){setError(errText(e,t("설정집을 저장하지 못했어요")));setSaving(false);}return;}
   if(!active.current)return;setSaving(false);
  }
  const next=[...books];if(editing<0)next.splice(insertAt??next.length,0,book);else next[editing]=book;
  const packed=packBooks(next)!;
  if(next.length>24||packed.fixed_settings.length+packed.progress_state.length+packed.entries.reduce((n,e)=>n+e.content.length,0)>80000){setError(t("설정집은 합쳐서 80,000자까지예요."));return;}
  onChange(packed);setUndo(null);setWorking(null);setEditing(-1);close();
 };
 return <section className="wt-lore" aria-label={t("설정집")}>
  <div className="wt-lore-attachline">
   <button ref={trigger} type="button" className="btn btn-w" disabled={busy} onClick={()=>edit()}>+ {t("설정집")}</button>
   <button ref={helpButton} type="button" className="wt-lore-help-button" aria-label={t("설정집이란?")} aria-expanded={help} aria-controls={helpId} onClick={()=>setHelp(v=>!v)}><span aria-hidden="true">?</span></button>

  </div>
  {!!books.length&&<ul className="wt-lore-attached-list">{books.map((book,index)=><li className="wt-lore-attached" key={index}>
   <button type="button" disabled={busy} onClick={()=>edit(index)}><b>{book.name||t("내 설정집")}</b>{book.source==="genre"&&book.confirmed&&<span>{t("고른 장르의 설정집")}</span>}{!book.confirmed&&<span>{t("설정을 다시 확인해 주세요")}</span>}</button>
   <button type="button" className="wt-lore-remove" aria-label={t("{name} 설정집 빼기",{name:book.name})} disabled={busy} onClick={()=>remove(index)}>{t("빼기")}</button>
  </li>)}</ul>}
  {undo&&<button type="button" className="btn-ghost" disabled={busy} onClick={()=>{setWorking({...undo.book,confirmed:false});setEditMode(false);setInsertAt(undo.index);setEditing(-1);setOpen(true);setUndo(null);}}>{t("방금 뺀 설정집 다시 붙이기")}</button>}
  {help&&<div id={helpId} className="wt-lore-help" role="region" aria-label={t("설정집이란?")} onKeyDown={e=>{if(e.key==="Escape"){e.stopPropagation();setHelp(false);helpButton.current?.focus();}}}>
   <div className="wt-lore-panelhead"><h3>{t("설정집이란?")}</h3><button type="button" className="wt-lore-close" aria-label={t("설정집 설명 닫기")} title={t("닫기")} onClick={()=>{setHelp(false);helpButton.current?.focus();}}><IconClose size={18}/></button></div>
   <p>{t("로어북이라고도 해요. 등장인물과 세계관, 지금까지의 이야기를 기억해 다음 화에도 이어 주는 설정집이에요.")}</p>
   <dl><div><dt>{t("고정 설정")}</dt><dd>{t("계속 지킬 인물·세계의 규칙. 예: 주인공은 높은 곳을 무서워해요.")}</dd></div><div><dt>{t("진행 상태")}</dt><dd>{t("이야기에 따라 달라지는 현재 상황. 예: 친구에게 비밀을 털어놓았어요.")}</dd></div></dl>
  </div>}
  {open&&<dialog ref={dialog} className="wt-lore-dialog" aria-label={t("설정집")} onCancel={e=>{e.preventDefault();if(!busy&&!saving)close();}}><div className="wt-lore-panel" ref={panel}>
   <div className="wt-lore-panelhead"><div><h3>{working?(editMode&&aiMode?t("AI로 설정집 만들기"):working.name||t("새 설정집")):t("설정집")}</h3></div>
    <button type="button" className="wt-lore-close" aria-label={t("설정집 닫기")} title={t("닫기")} disabled={busy||saving} onClick={close}><IconClose size={20}/></button></div>
   {busy&&!(editMode&&aiMode)&&<p role="status">{t("설정 추출 중…")}</p>}
   {!working?<>
    <button type="button" className="wt-lore-newtop" disabled={busy} onClick={startNew}><span aria-hidden="true">+</span><b>{t("새 설정집 작성")}</b><small>{t("직접 적거나, 긴 이야기를 올려 AI로 만들 수 있어요")}</small></button>
    {authenticated&&!!catalog?.personal_books?.length&&<label className="wt-lore-search">{t("설정집 찾기")}<input className="field" value={search} placeholder={t("설정집 찾기")} onChange={e=>setSearch(e.target.value)}/></label>}

    {authenticated&&<><h4>{t("내 설정집")}</h4><div className="wt-lore-catalog wt-lore-personal">
     {catalog?.personal_books?.filter(b=>(b.name+" "+b.fixed_settings).includes(search)).map(b=><button type="button" key={b.library_id} disabled={busy} onClick={()=>{setSaved(false);setInsertAt(null);setEditing(-1);setWorking(structuredClone(b));setEditMode(false);}}><b>{b.name}</b><span>{b.fixed_settings}</span></button>)}
    </div>{catalog&&!catalog.personal_books?.length&&<p className="muted">{t("저장한 설정집이 없어요")}</p>}</>}
    {/* 「기본 설정집」 목록은 뺐다 — 장르 칸과 이름이 겹쳐 무엇이 다른지 헷갈렸다. 장르를 고르면 그 장르의
       세계관·설정이 생성에 저절로 붙으니(run.py story_input_block), 여기는 내가 따로 만든 설정집만 둔다. */}
    {!catalog&&!error&&<p role="status">{t("설정집을 불러오고 있어요")}</p>}
    {catalog&&search&&!(catalog.personal_books||[]).some(b=>(b.name+" "+b.fixed_settings).includes(search))&&<p>{t("검색 결과 없음")}</p>}
    {!authenticated&&<p className="muted wt-lore-guest">{t("로그인하면 만든 설정집을 저장해 두고 다시 쓸 수 있어요.")}</p>}
   </>:editMode&&aiMode?<div className="wt-lore-ai">
    <p className="wt-lore-ai-lede">{t("긴 스토리를 올려주시면 AI가 자동으로 설정집을 만들어요!")}</p>
    <label className="wt-lore-ai-text"><span>{t("이야기")}</span>
     <textarea className="field" rows={12} maxLength={AI_MAX} value={aiText} disabled={busy} placeholder={t("소설·대본·지금까지의 줄거리를 붙여 넣어 주세요")} onChange={e=>{setAiText(e.target.value);setAiNote("");}}/></label>
    <div className="wt-lore-ai-tools">
     <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={()=>aiFile.current?.click()}>{t("글 파일 올리기")}</button>
     <input ref={aiFile} type="file" accept=".txt,.md,text/plain" hidden onChange={e=>{void readFile(e.target.files?.[0]);e.target.value="";}}/>
     {story.trim().length>0&&<button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={()=>{setAiText(story.trim().slice(0,AI_MAX));setAiNote("");}}>{t("적어 둔 이야기 가져오기")}</button>}
     <span className={`wt-lore-ai-count${aiText.length>=AI_MAX?" full":""}`}>{aiText.length.toLocaleString()} / {AI_MAX.toLocaleString()}{t("자")}</span>
    </div>
    {aiNote&&<p className="wt-lore-ai-note" role="status">{aiNote}</p>}
    {authenticated&&<label className="wt-lore-rights"><input type="checkbox" checked={rights} disabled={busy} onChange={e=>setRights(e.target.checked)}/>{t("입력한 글을 사용할 권리가 있어요.")}</label>}
    {busy&&<p className="wt-lore-ai-busy" role="status"><span className="spin"/>{t("설정집을 만드는 중이에요. 1~2분 걸릴 수 있어요.")}</p>}
    <div className="wt-lore-actions">
     <button type="button" className="btn btn-w" disabled={busy} onClick={()=>{setAiMode(false);setAiNote("");}}>{t("직접 입력으로")}</button>
     <button type="button" className="btn btn-p" disabled={busy||(authenticated&&(!rights||aiText.trim().length<30||catalog?.extract_left===0))} onClick={()=>void extract()}>{authenticated?t("AI로 설정집 만들기"):t("로그인")}</button>
    </div>
    {authenticated&&<p className="muted wt-lore-ai-left">{aiText.trim().length<30?t("30자 이상 넣어 주세요"):catalog?t("오늘 {n}회 남음 · 무료",{n:catalog.extract_left}):""}</p>}
   </div>:!editMode?<div className="wt-lore-read">
    <div className="wt-lore-read-meta"><span>{working.library_id?t("내 설정집"):working.source==="preset"||working.id?t("예시 설정집"):t("설정집")}</span>{(working.library_id?working.library_owner===owner:working.source!=="preset"&&!working.id)&&<button className="btn btn-w btn-sm" onClick={()=>{beforeEdit.current=structuredClone(working);setWorking(asEntries(working));setEditMode(true);}}>{t("수정")}</button>}</div>
    {!!working.fixed_settings&&<section><h4>{t("고정 설정")}</h4><p>{working.fixed_settings}</p></section>}
    {!!working.progress_state&&<section><h4>{t("진행 상태")}</h4><p>{working.progress_state}</p></section>}
    {!!working.entries.length&&<div className="wt-lore-read-entries">{working.entries.map((entry,i)=><details key={i} open={i===0}><summary>{entry.title}<small>{entry.always?t("항상 참고"):entry.keys.join(" · ")}</small></summary><p>{entry.content}</p>{entry.evidence&&<blockquote>{entry.evidence}</blockquote>}</details>)}</div>}
    <div className="wt-lore-actions"><button className="btn btn-w" onClick={()=>{setWorking(null);setEditing(-1);setInsertAt(null);}}>{t("목록으로")}</button><button className="btn btn-p" onClick={attach}>{editing>=0?t("확인"):t("이 설정집 사용")}</button></div>
   </div>:<fieldset className="wt-lore-editor" disabled={saving}>
    {!working.name&&!working.progress_state&&!working.entries.some(e=>e.title.trim()||e.content.trim())&&<div className="wt-lore-ai-offer">
     <p>{t("긴 스토리를 올려주시면 AI가 자동으로 설정집을 만들어요!")}</p>
     <button type="button" className="btn btn-w btn-sm" onClick={()=>{setAiMode(true);setError("");}}>{t("AI로 만들기")}</button>
    </div>}
    <label>{t("설정집 이름")}<input className="field" maxLength={100} value={working.name} onChange={e=>patch({name:e.target.value})}/></label>
    {working.source==="extracted"&&<p className="wt-lore-reviewnote">{t("AI 초안")}</p>}
    <div className="wt-lore-entries-head"><h4>{t("로어북")} <span>· {t("{n}개 항목",{n:working.entries.length})}</span></h4></div>
    <ol className="wt-lore-entry-list">
     {working.entries.map((e,i)=>{const set=(v:Partial<typeof e>)=>patch({entries:working.entries.map((x,j)=>j===i?{...x,...v}:x)});const name=e.title.trim()||t("항목 {n}",{n:i+1});return <li key={i} className="wt-lore-entry">
      <div className="wt-lore-entry-head">
       <input className="field wt-lore-entry-title" aria-label={t("항목 이름")} placeholder={t("이름 (예: 김시하)")} maxLength={100} value={e.title} onChange={ev=>set({title:ev.target.value})}/>
       <label className="wt-lore-always"><input type="checkbox" checked={e.always} onChange={ev=>set({always:ev.target.checked})}/>{t("항상 참고")}</label>
       <button type="button" className="wt-lore-icon" aria-label={t("{name} 삭제",{name})} title={t("항목 삭제")} onClick={()=>patch({entries:working.entries.filter((_,j)=>j!==i)})}><IconMinus size={18}/></button>
      </div>
      <textarea className="field" aria-label={t("{name} 내용",{name})} placeholder={t("설정 내용")} rows={4} maxLength={6000} value={e.content} onChange={ev=>set({content:ev.target.value})}/>
      {!e.always&&<input className="field wt-lore-entry-keys" aria-label={t("{name} 키워드",{name})} placeholder={t("키워드 (쉼표로 구분)")} value={e.keys.join(", ")}
        onChange={ev=>set({keys:ev.target.value.split(",").map(k=>k.trim()).filter(Boolean).slice(0,40)})}/>}
      {e.evidence&&<blockquote><span>{t("원문 근거")}</span>{e.evidence}</blockquote>}
     </li>;})}
    </ol>
    <button type="button" className="wt-lore-add" onClick={()=>patch({entries:[...working.entries,emptyEntry()]})}><IconPlus size={18}/>{t("항목 추가")}</button>
    {!!working.progress_state&&<label>{t("시작 시점 · 예전 설정집에만 있어요")}<textarea className="field" rows={3} maxLength={8000} value={working.progress_state} onChange={e=>patch({progress_state:e.target.value})}/></label>}
    <div className="wt-lore-actions"><button type="button" className="btn btn-w" onClick={()=>{setWorking(beforeEdit.current);setEditMode(false);if(!beforeEdit.current){setEditing(-1);setInsertAt(null);}}}>{beforeEdit.current?t("수정 취소"):t("취소")}</button>
     {saved&&<span role="status" className="wt-lore-saved">{t("저장했어요")}</span>}
     <button type="button" className="btn btn-w wt-lore-save" disabled={busy||saving||!working.name.trim()} onClick={()=>void saveMine()}>{saving?t("저장 중…"):t("내 설정집에 저장")}</button>
     <button type="button" className="btn btn-p" disabled={busy||saving||!working.fixed_settings.trim()&&!working.entries.some(e=>e.enabled&&e.content.trim())} onClick={()=>void attach()}>{t("확인하고 붙이기")}</button></div>
   </fieldset>}
   {error&&<p role="alert">{error} {!catalog&&<button type="button" onClick={()=>{setError("");void refresh();}}>{t("다시 시도")}</button>}</p>}
  </div></dialog>}
  {auth&&<AuthModal open onClose={()=>setAuth(false)}/>}
 </section>;
}
