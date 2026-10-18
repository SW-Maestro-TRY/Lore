import type{OwnedCustomMotion,PetDetail}from'../../lib/pet';
import{assetUrl}from'../../lib/assets';
export type FocusedChatResource=OwnedCustomMotion&{imageUrl:string};
const semantics=new Set(['base','eat','joy','sad','sick','pet','hello','sleep']);
/** Server inventory is the ownership authority. IDs/slots are never parsed or inferred. */
export function selectOwnedChatResource(pet:Pick<PetDetail,'customMotions'>|null|undefined,key:unknown):FocusedChatResource|null{
 if(typeof key!=='string'||!key||key==='base')return null;
 const exact=(pet?.customMotions??[]).filter(m=>m.id===key);if(exact.length!==1)return null;
 const m=exact[0];
 if(m.sourceKind!=='CUSTOM'||m.albumOnly||!m.semantic||!semantics.has(m.semantic)||typeof m.imageKey!=='string'||!m.imageKey.trim()||typeof m.anchorPoseKey!=='string'||!m.anchorPoseKey.trim())return null;
 if(!Number.isInteger(m.setId)||m.setId<=0||typeof m.embeddedProps!=='boolean'||typeof m.suppressRoomProps!=='boolean')return null;
 if(m.anchorsKey!==null&&(typeof m.anchorsKey!=='string'||!m.anchorsKey.trim()))return null;
 if(m.baseReferenceRound!==null&&(!Number.isInteger(m.baseReferenceRound)||m.baseReferenceRound<=0))return null;
 return {...m,imageUrl:assetUrl(m.imageKey)};
}
export function sameChatResource(a:FocusedChatResource,b:FocusedChatResource|null){
 return !!b&&a.id===b.id&&a.setId===b.setId&&a.imageKey===b.imageKey&&a.anchorsKey===b.anchorsKey&&a.anchorPoseKey===b.anchorPoseKey&&a.baseReferenceRound===b.baseReferenceRound&&a.embeddedProps===b.embeddedProps&&a.suppressRoomProps===b.suppressRoomProps&&a.planVersion===b.planVersion&&JSON.stringify(a.situations)===JSON.stringify(b.situations);
}

/** Validate raw evidence before mergeAnchors can fill legacy fixed values. */
export function completeCustomAnchorRaw(raw:unknown,poseKey:string):boolean{
 if(!raw||typeof raw!=='object')return false;
 const value=raw as Record<string,any>,canvas=value.canvas;
 const w=Array.isArray(canvas)?canvas[0]:canvas?.w,h=Array.isArray(canvas)?canvas[1]:canvas?.h;
 const positive=(n:unknown):n is number=>typeof n==='number'&&Number.isFinite(n)&&n>0;
 if(!positive(value.K)||!positive(w)||!positive(h)||value.K>h||!Number.isInteger(w)||!Number.isInteger(h))return false;
 const p=value.poses?.[poseKey];if(!p||typeof p!=='object')return false;
 const within=(n:unknown,max:number)=>typeof n==='number'&&Number.isFinite(n)&&n>=0&&n<=max;
 const point=(q:any)=>q&&within(q.x,w)&&within(q.y,h);
 const span=(q:any)=>q&&within(q.left_x,w)&&within(q.right_x,w)&&q.left_x<=q.right_x&&within(q.y,h);
 const bb=p.bbox;
 return !!(point(p.head_top)&&span(p.head_side)&&span(p.hand_front)&&span(p.feet)&&within(p.feet.center_x,w)&&bb&&within(bb.x,w)&&within(bb.y,h)&&positive(bb.w)&&positive(bb.h)&&bb.x+bb.w<=w&&bb.y+bb.h<=h);
}
const rawAnchorCache=new Map<string,Promise<unknown|null>>();
/** Same static-asset GET convention as useAnchors; no auth headers or second API. */
export function readCustomAnchorRaw(key:string,fetcher:typeof fetch=fetch):Promise<unknown|null>{
 const url=assetUrl(key),cached=rawAnchorCache.get(url);if(cached)return cached;
 const request=(async()=>{const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);
  try{const response=await fetcher(url,{credentials:'omit',signal:controller.signal});return response.ok?await response.json():null}catch{return null}finally{clearTimeout(timer)}
 })();rawAnchorCache.set(url,request);return request;
}
