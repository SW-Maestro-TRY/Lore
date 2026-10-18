import {request} from './api';
export const TUTORIAL_SCENES=['FEED','SNACK','CLEAN','BATH','GAME','NAP_SLEEP','NAP_WAKE'] as const;
export type TutorialScene=typeof TUTORIAL_SCENES[number];
export type StagePhase='ENTRY'|'AFTER_ACTION';
export type StageStatus='PENDING'|'READY'|'FAILED'|'UNCERTAIN'|'SUPERSEDED';
export interface StageSpeechView {id:number;step:TutorialScene;phase:StagePhase;status:StageStatus;contextRevision:string;line:string|null;source:'LLM'|'FALLBACK'|null;motionId:string|null;turnRef:number|null;updatedAt:string}
export const stageExpectedStep=(step:TutorialScene,phase:StagePhase):string=>phase==='ENTRY'?step:[...TUTORIAL_SCENES,'ATTENDANCE_INTRO'][TUTORIAL_SCENES.indexOf(step)+1];
export function isStageSpeech(value:unknown):value is StageSpeechView{
 if(!value||typeof value!=='object')return false;const v=value as StageSpeechView;
 return Number.isSafeInteger(v.id)&&v.id>0&&TUTORIAL_SCENES.includes(v.step)&&['ENTRY','AFTER_ACTION'].includes(v.phase)&&['PENDING','READY','FAILED','UNCERTAIN','SUPERSEDED'].includes(v.status)&&/^[a-f0-9]{64}$/i.test(v.contextRevision)&&[null,'LLM','FALLBACK'].includes(v.source)&&(v.line===null||typeof v.line==='string')&&(v.motionId===null||typeof v.motionId==='string')&&(v.turnRef===null||Number.isSafeInteger(v.turnRef)&&v.turnRef>0)&&typeof v.updatedAt==='string'&&Number.isFinite(Date.parse(v.updatedAt));
}
/** PREPARE and ACK share a URI, never their response parser or state mutation. */
export async function prepareTutorialStageSpeech(petId:number,step:TutorialScene,phase:StagePhase,signal?:AbortSignal):Promise<StageSpeechView>{
 const response=await request<unknown>(`/api/zzal/v1/me/pets/${petId}/tutorial/seen`,{method:'POST',body:{topic:step,purpose:'PREPARE_SPEECH',phase},signal});
 const speech=(response as {stageSpeech?:unknown}|null)?.stageSpeech;
 if(!isStageSpeech(speech)||speech.step!==step||speech.phase!==phase)throw new Error('안내를 준비하지 못했어요.');
 return speech;
}
