import {track, type EventContext} from '@common/analytics';
import type {Live} from '../yeoul/useHatch';

/** Only fixed action/screen tokens and scalar outcomes belong here, never user text. */
export function interactionMetrics(live: Pick<Live,'pet'>, sample=false, screen='focused_room', override?:EventContext['context']) {
 const context: EventContext = {context:override??(sample?'sample':live.pet?.firstIntro?.version===4&&!live.pet.firstIntro.completed?'tutorial':'real'),source_screen:screen};
 const emit=(name:string,props:Record<string,string|number|boolean>)=>{try{track(name,props,context)}catch{}};
 return {
  shown:(action:string)=>emit('zzal_ui_shown',{action,user_action:false}),
  attempt:(action:string)=>emit('zzal_user_action',{action,outcome:'attempt',user_action:true}),
  result:(action:string,outcome:'success'|'failure'|'uncertain'|'no_op')=>emit('zzal_action_result',{action,outcome,user_action:false}),
 };
}
