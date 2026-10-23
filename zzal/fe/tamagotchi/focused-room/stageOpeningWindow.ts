/** A presentation deadline, never a provider retry or a gameplay lock. */
export class StageOpeningWindow {
 private timers=new Map<string,ReturnType<typeof setTimeout>>();
 private expired=new Set<string>();
 constructor(private changed:()=>void,private clock={setTimeout:(callback:()=>void,delay:number)=>globalThis.setTimeout(callback,delay),clearTimeout:(timer:ReturnType<typeof setTimeout>)=>globalThis.clearTimeout(timer)}){}
 isExpired(key:string){return this.expired.has(key)}
 begin(key:string){if(this.timers.has(key)||this.expired.has(key))return;this.timers.set(key,this.clock.setTimeout(()=>{this.timers.delete(key);this.expired.add(key);this.changed()},4000));}
 cancel(key:string){const timer=this.timers.get(key);if(timer!==undefined)this.clock.clearTimeout(timer);this.timers.delete(key);this.expired.add(key)}
 cancelAll(){for(const key of this.timers.keys())this.cancel(key)}
 get pending(){return this.timers.size}
}
