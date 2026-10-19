'use client';
import {useEffect,useRef,useState} from 'react';
import {getAuthoredPersona,type PersonaSource} from '../../lib/pet';
import {useLive} from './useHatch';
import {personaChipLabel} from './personaChipCopy';
import {C,TAP_MIN} from './ui';
import {useZzalLocale} from '../../lib/locale';
import {PERSONA_EDITOR_EN,PERSONA_EDITOR_JA,PERSONA_EDITOR_KO} from './personaEditorCopy';
import PersonaSettingsEditor from './PersonaSettingsEditor';

/** Character info shows saved source; editing reuses the onboarding questions without restarting it. */
export default function PersonaEditor(){
 const locale=useZzalLocale(),copy=locale==='en'?PERSONA_EDITOR_EN:locale==='ja'?PERSONA_EDITOR_JA:PERSONA_EDITOR_KO;
 const live=useLive(),petId=live.petId;
 const[source,setSource]=useState<PersonaSource|null>(null),[editing,setEditing]=useState(false),[message,setMessage]=useState(''),[failed,setFailed]=useState(false),[attempt,setAttempt]=useState(0);const era=useRef(0);
 useEffect(()=>{const token=++era.current;setSource(null);setFailed(false);if(!petId)return;void getAuthoredPersona(petId).then(value=>{if(token===era.current)setSource(value)}).catch(()=>{if(token===era.current)setFailed(true)});return()=>{era.current++}},[petId,attempt]);
 useEffect(()=>{setEditing(false);setMessage('')},[petId]);
 if(!source)return <div role="status">{failed?copy.loadFailed:copy.loading}{failed&&<button style={{minHeight:TAP_MIN,minWidth:TAP_MIN}} onClick={()=>setAttempt(n=>n+1)}>{copy.retry}</button>}</div>;
 return <section className="zzal-persona-editor" data-part="persona-editor" style={{display:'flex',flexDirection:'column',gap:16,wordBreak:locale==='ko'?'keep-all':'normal',overflowWrap:'anywhere'}}>
  <p style={{margin:0}}>{copy.intro}</p><div><h4 style={{margin:'0 0 5px'}}>{copy.address}</h4><p data-part="persona-address" style={{margin:0}}>{source.callMe||copy.unknown}</p></div>
  {source.authoredPersona?source.authoredPersona.fields.map(field=><div key={field.fieldId}><h4 style={{margin:'0 0 5px'}}>{copy.labels[field.fieldId]}</h4><p style={{whiteSpace:'pre-wrap',margin:0}}>{[...field.chips.map(chip=>personaChipLabel(chip,locale)),field.rawText].filter(Boolean).join(' · ')||(locale==='en'?'Not entered':locale==='ja'?'未入力':'아직 입력하지 않았어요.')}</p></div>):<><p>{copy.legacy}</p>{live.pet?.world&&<p style={{whiteSpace:'pre-wrap'}}>{live.pet.world}</p>}{live.pet?.note&&<blockquote style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',margin:0}}>{live.pet.note}</blockquote>}</>}
  <p role="status" data-part="persona-save-status" style={{margin:0}}>{message}</p>
  <button data-action="persona-edit" type="button" onClick={()=>setEditing(true)} style={{minHeight:48,width:'100%',border:'1px solid '+C.accent,borderRadius:14,background:C.accent,color:C.accentInk,fontSize:16,fontWeight:700,padding:12}}>{locale==='en'?'Edit settings':locale==='ja'?'設定を変更する':'설정 수정하기'}</button>
  {editing&&<PersonaSettingsEditor key={petId} source={source} onClose={()=>setEditing(false)} onReload={()=>{setEditing(false);setMessage('');setAttempt(n=>n+1)}} onSaved={value=>{setEditing(false);setSource(value);setMessage(copy.saved);setAttempt(n=>n+1)}}/>}
 </section>;
}
