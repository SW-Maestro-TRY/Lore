'use client';
import { useEffect, useState } from 'react';

export interface SpriteGeometry {
  top: number; bottom: number; left: number; right: number;
  width: number; height: number; measured: boolean; loaded: boolean; failed?: boolean; footX?: number;
}
const cache = new Map<string, SpriteGeometry>();
const pending = new Map<string, Promise<SpriteGeometry>>();
const subscribers = new Set<() => void>();
function useGeometryRefresh(){const [epoch,setEpoch]=useState(0);useEffect(()=>{const refresh=()=>{if(!document.hidden)setEpoch(value=>value+1);};subscribers.add(refresh);document.addEventListener('visibilitychange',refresh);window.addEventListener('pageshow',refresh);return()=>{subscribers.delete(refresh);document.removeEventListener('visibilitychange',refresh);window.removeEventListener('pageshow',refresh);};},[]);return epoch;}
const fallback = (bottom: number): SpriteGeometry => ({top:0,bottom,left:0,right:1,width:313,height:350,measured:false,loaded:false});

function read(src: string, bottom: number): Promise<SpriteGeometry> {
  if (cache.has(src)) return Promise.resolve(cache.get(src)!);
  if (pending.has(src)) return pending.get(src)!;
  const task = new Promise<SpriteGeometry>(resolve => {
    let settled=false;
    const timer=setTimeout(()=>done({...fallback(bottom),loaded:true,failed:true}),4000);
    const done = (value: SpriteGeometry) => { if(settled){if(value.measured){cache.set(src,value);subscribers.forEach(refresh=>refresh());}return;}settled=true;clearTimeout(timer);if(!value.failed)cache.set(src,value);resolve(value); };
    const image = new Image(); image.crossOrigin = 'anonymous';
    image.onload = () => {
      const geometry = {...fallback(bottom),width:image.naturalWidth,height:image.naturalHeight,loaded:true};
      try {
        const canvas=document.createElement('canvas');canvas.width=geometry.width;canvas.height=geometry.height;
        const context=canvas.getContext('2d');if(!context){done(geometry);return;}
        context.drawImage(image,0,0);const data=context.getImageData(0,0,canvas.width,canvas.height).data;
        let left=canvas.width,right=-1,top=canvas.height,last=-1;
        for(let y=0;y<canvas.height;y++)for(let x=0;x<canvas.width;x++)if(data[(y*canvas.width+x)*4+3]>10){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);last=Math.max(last,y);}
        let footSum=0,footCount=0;for(let y=Math.max(top,last-Math.max(1,Math.floor((last-top)*0.025)));y<=last;y++)for(let x=0;x<canvas.width;x++)if(data[(y*canvas.width+x)*4+3]>10){footSum+=x+0.5;footCount++;}
        done(last<0?geometry:{...geometry,footX:footCount?footSum/footCount:canvas.width/2,top:top/canvas.height,bottom:(canvas.height-last-1)/canvas.height,left:left/canvas.width,right:(right+1)/canvas.width,measured:true});
      }catch{done(geometry);}
    };
    image.onerror=()=>{
      // CORS may forbid alpha inspection while the actual image remains displayable.
      const dimensions=new Image();dimensions.onload=()=>done({...fallback(bottom),width:dimensions.naturalWidth,height:dimensions.naturalHeight,loaded:true});
      dimensions.onerror=()=>done({...fallback(bottom),loaded:true,failed:true});dimensions.src=src;
    };
    image.src=src;
  });
  pending.set(src,task);void task.then(()=>pending.delete(src));return task;
}
export function useSpriteGeometry(src:string,bottom:number):SpriteGeometry {
  const epoch=useGeometryRefresh();
  const [result,setResult]=useState<{src:string;geometry:SpriteGeometry}>(()=>({src,geometry:cache.get(src)??fallback(bottom)}));
  useEffect(()=>{let active=true;if(src)void read(src,bottom).then(geometry=>{if(active)setResult({src,geometry});});return()=>{active=false;};},[src,bottom,epoch]);
  return result.src===src?result.geometry:cache.get(src)??fallback(bottom);
}
/** Every available owned pose shares one pixel scale; accessories only constrain
 * the common envelope, never renormalize each pose's body independently. */
export function useSpriteEnvelope(sources:readonly string[],bottom:number){
  const epoch=useGeometryRefresh();
  const key=JSON.stringify(sources);const [state,setState]=useState<{key:string;values:SpriteGeometry[]}|null>(null);
  useEffect(()=>{let active=true;const urls: string[]=JSON.parse(key);void Promise.all(urls.map(src=>read(src,bottom))).then(values=>{if(active)setState({key,values});});return()=>{active=false;};},[key,bottom,epoch]);
  const values=state?.key===key?state.values:[];
  return {ready:values.length===sources.length&&values.length>0,maxHeight:Math.max(1,...values.map(g=>g.height*(1-g.top-g.bottom))),minLeft:(values.length ? Math.min(...values.map(g=>g.width*g.left)) : 0), maxRight:Math.max(1,...values.map(g=>g.width*g.right)), maxWidth:Math.max(1,...values.map(g=>2*Math.max(g.width/2-g.width*g.left,g.width*g.right-g.width/2)))};
}
