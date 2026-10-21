import {isStageSpeech,stageExpectedStep,type StageSpeechView,type TutorialScene,type StagePhase,TUTORIAL_SCENES} from '../../lib/tutorialStageSpeech';
export type SpeechPresentation={line:string|null;source:'SERVER_LLM'|'SERVER_FALLBACK'|'SERVICE_FALLBACK'};
export function savedStageFor(records:readonly StageSpeechView[]|undefined,displayStep:string,expected?:{step:TutorialScene;phase:StagePhase}|null):StageSpeechView|undefined{
 return (records??[]).filter(isStageSpeech).filter(v=>stageExpectedStep(v.step,v.phase)===displayStep&&(!expected||v.step===expected.step&&v.phase===expected.phase)).sort((a,b)=>Date.parse(b.updatedAt)-Date.parse(a.updatedAt)||b.id-a.id)[0];
}
export function stageRequestFor(current:string|undefined,previous:string|undefined):{step:TutorialScene;phase:StagePhase}|null{
 if(!current)return null;
 if(previous&&TUTORIAL_SCENES.includes(previous as typeof TUTORIAL_SCENES[number])&&stageExpectedStep(previous as TutorialScene,'AFTER_ACTION')===current)return{step:previous as TutorialScene,phase:'AFTER_ACTION'};
 return TUTORIAL_SCENES.includes(current as typeof TUTORIAL_SCENES[number])?{step:current as TutorialScene,phase:'ENTRY'}:null;
}
export function stagePresentation(stored:StageSpeechView|undefined,fallback:string|null):SpeechPresentation{
 if(stored?.status==='READY'&&stored.line?.trim()&&stored.turnRef&&stored.source)return{line:stored.line,source:stored.source==='LLM'?'SERVER_LLM':'SERVER_FALLBACK'};
 return{line:fallback,source:'SERVICE_FALLBACK'};
}
/** Commit only when the scene was actually presented. Late READY cannot replace a read line. */
export class StageReadLedger{
 private lines=new Map<string,SpeechPresentation>();
 has(key:string){return this.lines.has(key);}
 peek(key:string,proposed:SpeechPresentation){return this.lines.get(key)??proposed;}
 commit(key:string,line:SpeechPresentation){if(!this.lines.has(key)){this.lines.set(key,line);if(this.lines.size>32)this.lines.delete(this.lines.keys().next().value!);}}
 clear(){this.lines.clear();}
}
