import type {PetDetail} from '../../lib/pet';
import type {ZzalLocale} from '../../lib/localeRouting';
import {localizeCustomMotionTitle} from '../../lib/customMotionTitles';
export interface AlbumMotion {id:string; label:string; description:string; imageKey:string; legacyKey?:string}
/** No ownership synthesis, ID parsing or semantic/anchor guesses. */
export function focusedAlbumInventory(pet:PetDetail|null,locale?:ZzalLocale):AlbumMotion[]{
 const result:AlbumMotion[]=(pet?.motions??[]).filter(m=>m.unlocked&&m.basicImageKey).map(m=>({id:`legacy:${m.key}`,label:m.label,description:'',legacyKey:m.key,imageKey:m.advanced?.status==='OPEN'&&m.advanced.imageKey?m.advanced.imageKey:m.basicImageKey!}));
 const ids=new Set<string>();
 for(const m of pet?.customMotions??[]){
  if(!m.id||!m.imageKey||m.sourceKind!=='CUSTOM'||ids.has(m.id))continue;
  ids.add(m.id);result.push({id:m.id,label:locale?localizeCustomMotionTitle(m.title,locale):m.title,description:m.description,imageKey:m.imageKey});
 }
 return result;
}
