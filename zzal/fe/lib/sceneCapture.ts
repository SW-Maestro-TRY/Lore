/** Rasterize the current room scene locally. No upload, chat text, or API writes. */
const OMIT = '[data-dev], [data-part="props"], [data-part="room-props"], [data-part="screen-props"], [data-share-exclude], [data-part="bubble"], [data-part="char-probe"], [data-part="hearts"], button, input, textarea, script, style';
function dataUrl(blob: Blob): Promise<string> { return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result));reader.onerror=()=>reject(new Error('image_read_failed'));reader.readAsDataURL(blob);}); }
async function image(source: string): Promise<HTMLImageElement> {const result=new Image();result.src=source;await result.decode();return result;}
async function inline(source: HTMLElement, copy: HTMLElement): Promise<void> {
 const css=getComputedStyle(source);for(const property of Array.from(css))copy.style.setProperty(property,css.getPropertyValue(property));
 copy.style.animation='none';copy.style.transition='none';
 if(source instanceof HTMLImageElement && copy instanceof HTMLImageElement){
  if(!source.complete||!source.naturalWidth)throw new Error('scene_image_not_ready');
  const canvas=document.createElement('canvas');canvas.width=source.naturalWidth;canvas.height=source.naturalHeight;
  const ctx=canvas.getContext('2d');if(!ctx)throw new Error('canvas_unavailable');
  try{ctx.drawImage(source,0,0);copy.src=canvas.toDataURL('image/png');}
  catch{const response=await fetch(source.currentSrc||source.src);if(!response.ok)throw new Error('scene_image_unavailable');copy.src=await dataUrl(await response.blob());}
  copy.removeAttribute('srcset');copy.removeAttribute('loading');
 }
 const sourceChildren=Array.from(source.children),copyChildren=Array.from(copy.children);
 for(let i=0;i<sourceChildren.length;i++){const original=sourceChildren[i],cloned=copyChildren[i];if(!(original instanceof HTMLElement)||!(cloned instanceof HTMLElement)){cloned?.remove();continue;}if(original.matches(OMIT)){cloned.remove();continue;}await inline(original,cloned);}
}
export async function captureRoomScene(stage: HTMLElement): Promise<Blob> {
 const rect=stage.getBoundingClientRect();if(rect.width<1||rect.height<1)throw new Error('scene_unavailable');
 const clone=stage.cloneNode(true) as HTMLElement;await inline(stage,clone);
 // Only visual scenery and the actor are exported; future stage labels must not leak dialogue or names.
 const textNodes:Node[]=[];const walker=document.createTreeWalker(clone,NodeFilter.SHOW_TEXT);while(walker.nextNode())textNodes.push(walker.currentNode);textNodes.forEach(node=>node.parentNode?.removeChild(node));
 clone.setAttribute('xmlns','http://www.w3.org/1999/xhtml');Object.assign(clone.style,{position:'relative',left:'0',top:'0',margin:'0',width:rect.width+'px',height:rect.height+'px',transform:'none'});
 const serialized=new XMLSerializer().serializeToString(clone);
 const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="${rect.width}" height="${rect.height}"><foreignObject x="0" y="0" width="100%" height="100%">${serialized}</foreignObject></svg>`;
 const source=await image('data:image/svg+xml;charset=utf-8,'+encodeURIComponent(svg));
 const canvas=document.createElement('canvas');canvas.width=1080;canvas.height=Math.round(1080*rect.height/rect.width);const ctx=canvas.getContext('2d');if(!ctx)throw new Error('canvas_unavailable');ctx.drawImage(source,0,0,canvas.width,canvas.height);
 return new Promise((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(blob):reject(new Error('scene_encode_failed')),'image/png'));
}
export async function composeSceneCard(scene: Blob, logoUrl: string): Promise<File> {
 const picture=await image(await dataUrl(scene));const logo=await image(logoUrl);const canvas=document.createElement('canvas');canvas.width=picture.naturalWidth;const footer=Math.round(canvas.width*.17);canvas.height=picture.naturalHeight+footer;
 const ctx=canvas.getContext('2d');if(!ctx)throw new Error('canvas_unavailable');ctx.fillStyle='#fffdf8';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.drawImage(picture,0,0);const width=canvas.width*.48,height=width*logo.naturalHeight/logo.naturalWidth;ctx.drawImage(logo,(canvas.width-width)/2,picture.naturalHeight+(footer-height)/2,width,height);
 return new Promise((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(new File([blob],'lore-character-room.png',{type:'image/png'})):reject(new Error('scene_encode_failed')),'image/png'));
}
