import{useEffect,useState,type CSSProperties}from'react';
import{completeCustomAnchorRaw,readCustomAnchorRaw,type FocusedChatResource}from'./focusedChatMotion';
import type{Live}from'../yeoul/useHatch';
import{useSpriteGeometry}from'../../lib/spriteGeometry';
import{useAnchors,mergeAnchors}from'../props/anchors';
import{poseAnchors}from'../props/layout';
import{assetUrl}from'../../lib/assets';
/** One generic server/alpha body normalization for first meeting and every room. */
export function useFocusedGeometry(live:Live,size:{width:number;height:number},pose:string,initialHeight:number,custom?:FocusedChatResource|null){
 const pet=live.pet;
  const base = live.img('base') || '', src = custom?.imageUrl || live.img(pose) || base;
  const reference = useSpriteGeometry(base, 0.05), geometry = useSpriteGeometry(src, 0.05);
  // One base-body budget. A crouched/sleeping pose or prop never renormalizes the character's size.
  const compact=size.height<initialHeight*.78,short=size.height<=630,tiny=size.height<=380;
  const actorBoxWidth=size.width*(compact?(tiny?.49:.53):short?.70:.75);
  const actorBoxHeight=size.height*(compact?(tiny?.62:.59):short?.52:.58);
  const body=Math.min(actorBoxWidth,actorBoxHeight)*.95;
  // Normal/legacy loading keeps its old fallback. Generated resources require raw evidence first.
  const anchors=useAnchors(pet?.anchorsKey);
  const customKey=custom?.anchorsKey??null,customPose=custom?.anchorPoseKey??'';
  const rawIdentity=JSON.stringify([customKey,customPose]);
  const [rawState,setRawState]=useState<{identity:string;raw:unknown|null;done:boolean}|null>(null);
  useEffect(()=>{let alive=true;
   if(!customKey){setRawState({identity:rawIdentity,raw:null,done:true});return;}
   setRawState({identity:rawIdentity,raw:null,done:false});
   void readCustomAnchorRaw(customKey).then(raw=>{if(alive)setRawState({identity:rawIdentity,raw,done:true})});
   return()=>{alive=false};
  },[rawIdentity,customKey]);
  const rawMatches=rawState?.identity===rawIdentity;
  const customAnchors=custom&&rawMatches&&rawState.done&&completeCustomAnchorRaw(rawState.raw,customPose)?mergeAnchors(rawState.raw):null;
  const customPending=!!customKey&&(!rawMatches||!rawState.done);
  // Explicit physical slot only. No semantic alias or fixed/base slot can establish custom authority.
  const poseAnchor=custom?(customAnchors?.serverPoses?.includes(customPose)?customAnchors.poses[customPose]:null):anchors.source==='server'?poseAnchors(anchors.anchors,pose):null;
  const anchorCanvas=customAnchors?.canvas??anchors.anchors.canvas;
  const alphaReferenceBody=reference.measured?reference.height*(1-reference.top-reference.bottom):reference.height*.9;
  // Seeded K/canvas carries the frozen baseReferenceRound; do not mutate the pet's current base image/round.
  const referenceBody=custom?(poseAnchor?geometry.height*customAnchors!.K/anchorCanvas[1]:alphaReferenceBody):anchors.source==='server'?reference.height*anchors.anchors.K/anchorCanvas[1]:alphaReferenceBody;
  const customFailed=!!custom&&geometry.loaded&&(!geometry.measured||!!geometry.failed);
  const customReady=!!custom&&reference.loaded&&geometry.loaded&&geometry.measured&&!geometry.failed&&!customPending;
  const customAnchorMode=!custom?null:!customReady?'loading':poseAnchor?'server':'alpha-fallback';
  const scale = body / Math.max(1, referenceBody);
  const foot=compact?size.height-67-16-8:size.height*(short?.77:.83)-16-8;
  const width = geometry.width * scale, height = geometry.height * scale;
  const alphaLeft = geometry.measured ? geometry.left * geometry.width * scale : 0;
  const alphaRight = geometry.measured ? geometry.right * geometry.width * scale : width;
  const naturalLeft = size.width * (compact?(tiny?.25:.26):.315) - (poseAnchor?poseAnchor.feet.center_x/anchorCanvas[0]*width:width/2);
  const left = Math.max(6 - alphaLeft, Math.min(size.width - 6 - alphaRight, naturalLeft));
  const top = foot - geometry.height * (poseAnchor?poseAnchor.feet.y/anchorCanvas[1]:1-geometry.bottom) * scale;
  const actorStyle: CSSProperties = { width, height, left, top };
  const rewardBackground = pet?.attendanceRewards?.background.owned.find(item => item.key === pet.background)?.url;
  const backgroundUrl = rewardBackground ? assetUrl(rewardBackground) : null;
 return{base,src,reference,geometry,body,compact,short,tiny,scale,foot,width,height,alphaLeft,alphaRight,left,top,actorStyle,backgroundUrl,customReady,customFailed,customAnchorMode};
}
