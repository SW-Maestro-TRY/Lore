'use client';
import {useLayoutEffect,useRef,useState} from 'react';
import {useSpriteGeometry} from '../../lib/spriteGeometry';
import { useAnchors } from '../props/anchors';
import { resolveScene, type PropScene, type SituationRow } from '../props/situations';
import { SITUATION_TABLE } from '../props/table';
import { layoutProp, layoutRoomProp, layoutScreenProp, unitPxOfWidth } from '../props/layout';
import { stageUrl } from '../props/spec';
import { confirmedSpec } from '../props/catalog';
import type { Live } from '../yeoul/useHatch';
import type { FocusedVisual } from './useFocusedExperience';
/** Existing situation/catalog/layout contract, with this renderer's measured foot line. */
export function FocusedProps({live,visual,width,height,actor,footFloor,renderTrash=true,speechVisible=false}:{speechVisible?:boolean;renderTrash?:boolean;live:Live;visual:FocusedVisual;width:number;height:number;actor:{left:number;top:number;width:number;height:number};footFloor:number}){
 const anchors=useAnchors(live.pet?.anchorsKey);
 const root=useRef<HTMLDivElement>(null),[dockTop,setDockTop]=useState(height);
 const sprite=useSpriteGeometry(live.img(visual.pose)||live.img('base')||'',.05);
 useLayoutEffect(()=>{
  const host=root.current?.closest<HTMLElement>('.focused-room'),dock=host?.querySelector<HTMLElement>('.fr-dock');
  if(!host||!dock){setDockTop(height);return;}
  const measure=()=>setDockTop(dock.getBoundingClientRect().top-host.getBoundingClientRect().top);
  measure();const observer=new ResizeObserver(measure);observer.observe(dock);return()=>observer.disconnect();
 },[width,height]);
 // Bath foam and cleaning dust belong to the visible floor, not behind the composer.
 // Preserve stage aspect and rhythm. Only an over-tall floor-effect sequence
 // gets one uniform fit scale so its upper particles are not cropped on small phones.
 const visibleFoot=actor.top+actor.height*(1-sprite.bottom);
 const effectBottom=Math.max(0,Math.min(height,dockTop-8,visibleFoot+12));
 const trash=renderTrash?Math.max(0,Math.min(4,live.pet?.gauges?.trash??0)):0;
 // Same optional sweep signal as the original Room. No signal is authored while SWEEP_SIGNAL is empty.
 const signalSpec=visual.signal?confirmedSpec(visual.signal):null;
 const signal:SituationRow|null=signalSpec?{id:'__focused_sweep__',pose:'*',prop:visual.signal,anchor:signalSpec.anchor,layer:signalSpec.unit==='screen'?'screen':'char',priority:1,status:'confirmed'}:null;
 const table=signal?[...SITUATION_TABLE,signal]:SITUATION_TABLE;
 const active=[...(trash?['trash_always']:[]),...(visual.situation?[visual.situation]:[]),...(signal?[signal.id]:[])].filter(id=>!speechVisible||!table.find(row=>row.id===id)?.prop?.startsWith('bubble_'));
 const scene:PropScene={pose:visual.pose,active,stages:{trash,...(visual.prop?{[visual.prop]:visual.stage}:{})}};
 const items=resolveScene(table,scene);
 return <div ref={root} className="fr-props" aria-hidden="true">{items.map(r=>{
  const screen=r.spec.unit==='screen',fixed=r.row.layer==='floor'||r.row.layer==='room';
  const stage={width,height,footFloor};const box=screen?layoutScreenProp(r.spec,r.stage,stage):fixed?layoutRoomProp(r.spec,r.stage,stage,unitPxOfWidth(anchors.anchors,actor.width),actor.width):layoutProp(r.spec,r.stage,{boxW:actor.width,boxH:actor.height,anchors:anchors.anchors,pose:visual.pose});
  if(!box)return null;
  // The legacy outside-hand placement assumes a centered character. This room's
  // left-hand composition can leave less space than the prop width. Keep the
  // catalog size/hand height, relaxing only the outward offset at the stage edge.
  // The hand remains covered by the prop; the character geometry never changes.
  const naturalLeft=box.left+(screen||fixed?0:actor.left);
  const handOutside=!screen&&!fixed&&r.spec.anchor==='hand_front'&&r.spec.outside;
  const handBoxes=handOutside?r.spec.stages.map(propStage=>layoutProp(r.spec,propStage,{boxW:actor.width,boxH:actor.height,anchors:anchors.anchors,pose:visual.pose})).filter((value):value is NonNullable<typeof value>=>value!==null):[];
  const outerLeft=Math.min(...handBoxes.map(value=>value.left+actor.left));
  const outerRight=Math.max(...handBoxes.map(value=>value.left+actor.left+value.width));
  // One correction for the whole sequence: the hand contact never jumps as bowls empty.
  const shift=handBoxes.length&&outerRight-outerLeft<=width-12?Math.max(6-outerLeft,Math.min(0,width-6-outerRight)):0;
  const floorEffect=screen&&(r.spec.key==='bath'||r.spec.key==='dust');
  const effectHeights=floorEffect?r.spec.stages.map(propStage=>layoutScreenProp(r.spec,propStage,stage)?.height??0):[];
  const effectScale=floorEffect?Math.min(1,Math.max(0,effectBottom-8)/Math.max(1,...effectHeights)):1;
  let propWidth=box.width*effectScale,propHeight=box.height*effectScale;
  let left=floorEffect?box.left+(box.width-propWidth)/2:naturalLeft+shift;
  let top=floorEffect?effectBottom-propHeight:box.top+(screen||fixed?0:actor.top);
  if(r.spec.key==='win_star'&&!screen&&!fixed){
   // Fit the full sequence with one correction so later star frames never jump at the edge.
   const frames=r.spec.stages.map(value=>layoutProp(r.spec,value,{boxW:actor.width,boxH:actor.height,anchors:anchors.anchors,pose:visual.pose})).filter((value):value is NonNullable<typeof value>=>value!==null);
   const minX=Math.min(...frames.map(value=>value.left+actor.left)),maxX=Math.max(...frames.map(value=>value.left+actor.left+value.width));
   const minY=Math.min(...frames.map(value=>value.top+actor.top)),maxY=Math.max(...frames.map(value=>value.top+actor.top+value.height));
   const fit=Math.min(1,Math.max(0,width-12)/Math.max(1,maxX-minX),Math.max(0,height-12)/Math.max(1,maxY-minY));
   const shiftX=Math.max(6-minX,Math.min(0,width-6-(minX+(maxX-minX)*fit)));
   const shiftY=Math.max(6-minY,Math.min(0,height-6-(minY+(maxY-minY)*fit)));
   left=minX+(left-minX)*fit+shiftX;top=minY+(top-minY)*fit+shiftY;propWidth*=fit;propHeight*=fit;
  }
  return <img key={`${r.row.id}:${r.stage.key}`} data-fr-prop={r.spec.key} data-stage={r.stage.n} alt="" src={stageUrl(r.stage)} onError={event=>{event.currentTarget.hidden=true}} style={{position:'absolute',left,top,width:propWidth,height:propHeight,transform:box.mirrored?'scaleX(-1)':undefined}}/>;
 })}</div>;
}
