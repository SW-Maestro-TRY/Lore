import type { CareAction } from './pet';
import type { FirstIntroKey } from '../tamagotchi/yeoul/firstIntro';
export type PendingCare={petId:number;action:CareAction;firstIntroStep?:FirstIntroKey;requestId:string};
const actions=['FEED','SNACK','CLEAN','BATH','PET','MEDICINE'];
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const key=(petId:number)=>`zzal-care-pending-v1:${petId}`;
/** Pending identity is not success evidence. No automatic POST follows restoration. */
export function readPendingCare(petId:number,storage?:Storage):PendingCare|null{
 try{const s=storage??sessionStorage;const p=JSON.parse(s.getItem(key(petId))??'null');return p&&p.petId===petId&&actions.includes(p.action)&&uuid.test(p.requestId)&&(!p.firstIntroStep||['FEED','SNACK','CLEAN','BATH','PET'].includes(p.firstIntroStep))?p:null}catch{return null}
}
export function savePendingCare(p:PendingCare,storage?:Storage):boolean{
 try{const s=storage??sessionStorage;s.setItem(key(p.petId),JSON.stringify(p));return s.getItem(key(p.petId))===JSON.stringify(p)}catch{return false}
}
export function clearPendingCare(p:PendingCare,storage?:Storage){try{const s=storage??sessionStorage;if(readPendingCare(p.petId,s)?.requestId===p.requestId)s.removeItem(key(p.petId))}catch{/* Never manufacture success from browser storage. */}}
/** A visual consumption marker only. It never unlocks a tutorial or changes server state. */
export function claimCarePlayback(petId:number,id:string,storage?:Storage):boolean{
 try{if(!uuid.test(id))return false;const s=storage??sessionStorage,k=`zzal-care-shown-v1:${petId}`,raw=JSON.parse(s.getItem(k)??'[]'),seen=Array.isArray(raw)?raw.filter(v=>typeof v==='string'&&uuid.test(v)):[];if(seen.includes(id))return false;s.setItem(k,JSON.stringify([...seen,id].slice(-64)));return true}catch{return false}
}

const definitiveCareCodes=new Set(['ZZAL_PET_NOT_FOUND','ZZAL_PET_NOT_ALIVE','ZZAL_PET_SLEEPING','ZZAL_CARE_NOT_NEEDED','ZZAL_NO_FOOD','ZZAL_SICK_REFUSES','ZZAL_BATH_DONE_TODAY','ZZAL_TRAVELING','ZZAL_TUTORIAL_STEP_MISMATCH','ZZAL_ACTION_REQUEST_CONFLICT']);
export function definiteCareRejection(status:number,code:string|null|undefined){return status>=400&&status<500&&status!==408&&!!code&&definitiveCareCodes.has(code)}
