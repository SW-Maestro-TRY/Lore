import type{CareAction,CareMotionPlayback,PetDetail}from'../../lib/pet';
import{selectOwnedChatResource,type FocusedChatResource}from'./focusedChatMotion';
export type SelectedCarePlayback={kind:'basic'}|{kind:'custom';resource:FocusedChatResource};
const basicSemantic:Record<CareAction,string>={FEED:'eat',SNACK:'eat',CLEAN:'base',BATH:'base',PET:'pet',MEDICINE:'eat'};
const metadataKeys=['id','setId','order','title','description','semantic','albumOnly','imageKey','anchorsKey','anchorPoseKey','baseReferenceRound','embeddedProps','suppressRoomProps','sourceKind','planVersion']as const;
const sameSituations=(a:unknown,b:unknown)=>JSON.stringify(a)===JSON.stringify(b);
/** New plans must explicitly authorize this care situation. Legacy plans only establish PET. */
export function authorizesCare(resource:FocusedChatResource,action:CareAction):boolean{
 if(resource.planVersion===undefined||resource.planVersion==='CUSTOM_MOTION_PLAN_V1')return action==='PET'&&resource.semantic==='pet'&&(resource.situations===undefined||(Array.isArray(resource.situations)&&resource.situations.length===0));
 return resource.planVersion==='CUSTOM_MOTION_PLAN_V2'&&Array.isArray(resource.situations)&&resource.situations.length>0&&new Set(resource.situations).size===resource.situations.length&&resource.situations.every(s=>Object.hasOwn(basicSemantic,s))&&resource.situations.includes(action);
}
/** A missing/invalid selection resolves to that action's existing basic presentation, never a guessed custom. */
export function selectCarePlayback(pet:PetDetail|null|undefined,playback:CareMotionPlayback|null|undefined,expectedPetId:number|null,action:CareAction):SelectedCarePlayback|null{
 if(!pet||pet.petId!==expectedPetId||pet.clock?.sleeping||pet.trip)return null;
 if(!playback||playback.situationKey!==action||playback.basicSemantic!==basicSemantic[action])return {kind:'basic'};
 if(playback.sourceKind==='BASIC')return {kind:'basic'};
 if(playback.sourceKind!=='CUSTOM'||!playback.resource||playback.resource.id!==playback.motionId)return {kind:'basic'};
 const selected=selectOwnedChatResource(pet,playback.motionId),resource=playback.resource;
 if(!selected||!authorizesCare(selected,action)||!metadataKeys.every(key=>selected[key]===resource[key])||!sameSituations(selected.situations,resource.situations))return {kind:'basic'};
 const exact=selectOwnedChatResource({customMotions:[resource]},playback.motionId);
 return exact&&authorizesCare(exact,action)?{kind:'custom',resource:exact}:{kind:'basic'};
}
/** Compatibility caller for previously integrated PET adapter tests. */
export function selectPetCarePlayback(pet:PetDetail|null|undefined,playback:CareMotionPlayback|null|undefined,expectedPetId:number|null){return selectCarePlayback(pet,playback,expectedPetId,'PET');}
