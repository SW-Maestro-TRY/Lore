'use client';
import{useEffect,useLayoutEffect,useRef,useState,type ReactNode}from'react';
import{useLive}from'../yeoul/useHatch';
import{useFocusedGeometry}from'./useFocusedGeometry';
import{SpeechBubble}from'./FocusedRoom';
import{useZzalLocale}from'../../lib/locale';
/** Pure presentation: the existing FirstMeeting API state machine remains the owner. */
export default function FocusedMeetingScene({name,speech,stage,children}:{name:string;speech:string;stage:'discovery'|'address'|'greeting';children:ReactNode}){
 const live=useLive(),locale=useZzalLocale(),root=useRef<HTMLElement>(null),initial=useRef(typeof window==='undefined'?844:innerHeight),[size,setSize]=useState({width:390,height:844}),[pose,setPose]=useState('base');
 useLayoutEffect(()=>{const e=root.current;if(!e)return;const measure=()=>{const r=e.getBoundingClientRect();setSize({width:r.width,height:r.height})};measure();const observer=new ResizeObserver(measure);observer.observe(e);return()=>observer.disconnect()},[]);
 useEffect(()=>{const key=stage==='discovery'?'hello':stage==='greeting'?(live.img('petted')?'petted':'pet'):'base';setPose(live.img(key)?key:'base');const timer=setTimeout(()=>setPose('base'),1800),hidden=()=>{if(document.hidden){clearTimeout(timer);setPose('base')}};document.addEventListener('visibilitychange',hidden);return()=>{clearTimeout(timer);document.removeEventListener('visibilitychange',hidden)}},[stage,live.petId]);
 const g=useFocusedGeometry(live,size,pose,initial.current),budget=Math.max(20,Math.floor(size.width/15)*Math.floor(size.height/90));
 return <section ref={root} className="focused-room fr-meeting-room" aria-labelledby="first-meeting-name" data-focused-meeting data-compact={g.compact?'true':undefined}><div className="fr-scene"><div className="fr-world">{g.backgroundUrl&&<img src={g.backgroundUrl} alt=""/>}</div>{g.src&&g.reference.loaded&&g.geometry.loaded&&!g.geometry.failed&&<img className="fr-actor" data-fr-actor src={g.src} alt={name} style={g.actorStyle}/>}</div><header className="fr-header"><h1 id="first-meeting-name">{name}</h1></header>{speech&&<SpeechBubble line={speech} bounds={{left:g.left+g.alphaLeft,right:g.left+g.alphaRight,top:g.top+g.geometry.top*g.geometry.height*g.scale,bottom:g.foot}} budget={budget} style={{left:size.width*.48,top:90,width:size.width*.45}} previous={locale==='ko'?'이전':locale==='en'?'Previous':'前へ'} next={locale==='ko'?'다음':locale==='en'?'Next':'次へ'}/>}<div className="fr-meeting-controls">{children}</div></section>;
}
