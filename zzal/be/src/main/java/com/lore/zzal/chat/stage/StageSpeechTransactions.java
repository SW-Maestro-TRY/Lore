package com.lore.zzal.chat.stage;
import com.lore.common.exception.*;import com.lore.zzal.chat.*;import com.lore.zzal.chat.delivery.ZzalChatDeliveryRepository;
import com.lore.zzal.chat.line.*;import com.lore.zzal.chat.persona.PersonaSheetBuilder;import com.lore.zzal.chat.prompt.*;
import com.lore.zzal.chat.session.*;import com.lore.zzal.pet.*;import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
@Service
public class StageSpeechTransactions {
 private final ZzalStageSpeechRepository rows;private final ZzalPetRepository pets;private final PetService petService;
 private final ChatService chat;private final PersonaSheetBuilder sheets;private final ZzalChatSessionRepository sessions;
 private final ZzalChatTurnRepository turns;private final ZzalChatDeliveryRepository deliveries;private final JsonMapper json;
 public StageSpeechTransactions(ZzalStageSpeechRepository rows,ZzalPetRepository pets,PetService petService,ChatService chat,PersonaSheetBuilder sheets,ZzalChatSessionRepository sessions,ZzalChatTurnRepository turns,ZzalChatDeliveryRepository deliveries,JsonMapper json){
  this.rows=rows;this.pets=pets;this.petService=petService;this.chat=chat;this.sheets=sheets;this.sessions=sessions;this.turns=turns;this.deliveries=deliveries;this.json=json;
 }
 public record Claim(boolean acquired,Long id,UUID token,ChatContext context,ZzalStageSpeech.View view){}
 public static String sha(String value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private String revision(ZzalPet pet){return sha("tutorial-action-guidance-v1:"+json.writeValueAsString(sheets.build(pet)));}
 private String revision(ZzalPet pet,ConversationScene.Step step){return discovery(step)?sha(json.writeValueAsString(sheets.build(pet))):revision(pet);}
 private boolean discovery(ConversationScene.Step step){return step==ConversationScene.Step.FIRST_MEETING_DISCOVERY;}
 private boolean discoveryPosition(ZzalPet pet){return pet.isAlive()&&!pet.isSleeping()&&!pet.isTraveling()&&
   pet.getFirstMeetingState()==com.lore.zzal.pet.persona.FirstMeeting.State.DISCOVERED;}
 private String position(ZzalPet pet,ConversationScene.Step step){return discovery(step)?"FIRST_MEETING":currentStep(pet);}
 private String currentStep(ZzalPet pet){return FirstIntro.sequence(pet.getFirstIntroVersion()).get(pet.getFirstIntroStep()).name();}
 private boolean validPosition(ZzalPet pet,ConversationScene.Step step,ConversationScene.Beat phase){
  if(discovery(step))return phase==ConversationScene.Beat.ENTRY&&discoveryPosition(pet);
  if(!pet.isAlive()||!pet.isFirstMeetingComplete()||pet.getFirstIntroVersion()!=4||!pet.hasFirstIntro()||pet.isSleeping()||pet.isTraveling())return false;
  if(TutorialEndingSpeech.supports(step))return phase==ConversationScene.Beat.ENTRY&&pet.introAt(TutorialEndingSpeech.position(step));
  int i=pet.firstIntroIndex(FirstIntro.Step.valueOf(step.name()));int expected=phase==ConversationScene.Beat.ENTRY?i:i+1;
  if(i<0||pet.getFirstIntroStep()!=expected)return false;
  return true;
 }
 private boolean visible(ZzalPet pet,ZzalStageSpeech row){return validPosition(pet,row.step(),row.phase())&&row.expectedStep().equals(position(pet,row.step()))&&row.revision().equals(revision(pet,row.step()));}
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public Claim claim(Long owner,Long petId,ConversationScene.Step step,ConversationScene.Beat phase,Instant realNow){
  if(discovery(step))throw new BusinessException(ErrorCode.ZZAL_TUTORIAL_STEP_MISMATCH);
  return claimScene(owner,petId,step,phase,realNow,null);
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public Claim claimDiscovery(Long owner,Long petId,Instant realNow,String language){
  return claimScene(owner,petId,ConversationScene.Step.FIRST_MEETING_DISCOVERY,ConversationScene.Beat.ENTRY,realNow,language);
 }
 public record Batch(List<Claim> claims){}
 /** Freeze every scene under the existing pet lock, before the single external request. */
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public Batch claimBatch(Long owner,Long petId,ConversationScene.Step requested,ConversationScene.Beat phase,Instant realNow){
  if(requested==null||phase==null||discovery(requested))throw new BusinessException(ErrorCode.ZZAL_TUTORIAL_STEP_MISMATCH);
  var pet=petService.awake(owner,petId,realNow);
  if(!validPosition(pet,requested,phase))throw new BusinessException(ErrorCode.ZZAL_TUTORIAL_STEP_MISMATCH);
  // Existing claims are durable, including uncertain outcomes. Only a legacy care-only cache
  // may acquire its never-before-generated ending group on an explicit ending request.
  var existing=rows.findByPetIdAndIntroVersion(petId,4);
  boolean careCached=existing.stream().anyMatch(r->!discovery(r.step()));
  if(careCached&&(!TutorialEndingSpeech.supports(requested)||existing.stream().anyMatch(r->TutorialEndingSpeech.supports(r.step()))))return new Batch(List.of());
  var session=sessions.findByPetIdAndDayOfAndSlot(petId,AwakeClock.dateOf(pet.getHatchedAt()),ChatSlot.CONTINUOUS)
    .orElseThrow(()->new BusinessException(ErrorCode.ZZAL_CHAT_SESSION_STALE));
  var scenes=new ArrayList<ConversationScene>();
  scenes.add(new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE,requested,phase));
  if(!careCached)for(var step:ConversationScene.Step.values())if(!discovery(step)&&!TutorialEndingSpeech.supports(step))for(var beat:ConversationScene.Beat.values()){
   if(step!=requested||beat!=phase)scenes.add(new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE,step,beat));
  }
  for(var step:TutorialEndingSpeech.STEPS)if(step!=requested)scenes.add(new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE,step,ConversationScene.Beat.ENTRY));
  var claims=new ArrayList<Claim>();String revision=revision(pet);
  for(var scene:scenes){
   var context=chat.tutorialStageContext(pet,scene.step(),scene.beat(),realNow);String snapshot=json.writeValueAsString(context);
   String expected;
   if(TutorialEndingSpeech.supports(scene.step()))expected=TutorialEndingSpeech.position(scene.step()).name();
   else{int index=pet.firstIntroIndex(FirstIntro.Step.valueOf(scene.step().name()))+(scene.beat()==ConversationScene.Beat.ENTRY?0:1);
    expected=FirstIntro.sequence(4).get(index).name();}
   var row=rows.saveAndFlush(new ZzalStageSpeech(owner,petId,4,scene.step(),scene.beat(),expected,session.getId(),snapshot,sha(snapshot),revision,realNow));
   claims.add(new Claim(true,row.id(),row.token(),context,row.view(validPosition(pet,scene.step(),scene.beat()),false)));
  }
  return new Batch(List.copyOf(claims));
 }
 /** Cache only: even a currently visible line has no PET turn until explicit activation. */
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void completeBatch(Long owner,Long petId,Batch batch,List<GeneratedLine> generated,Instant now){
  var pet=pets.findByIdForUpdate(petId).filter(p->p.isOwnedBy(owner)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
  if(batch.claims().size()!=generated.size())throw new IllegalArgumentException("Incomplete tutorial batch");
  for(int i=0;i<generated.size();i++){
   var claim=batch.claims().get(i);var line=generated.get(i);
   var row=rows.lockById(claim.id()).filter(r->r.petId().equals(petId)&&r.ownerId().equals(owner)).orElseThrow();
   if(!row.hash().equals(sha(json.writeValueAsString(claim.context()))))throw new IllegalStateException("Stage context differs from frozen snapshot");
   boolean success=Set.of("ok","retried_ok","truncated").contains(line.outcome().code())&&line.text()!=null&&!line.text().isBlank();
   var status=!row.revision().equals(revision(pet))?ZzalStageSpeech.Status.SUPERSEDED:success?ZzalStageSpeech.Status.READY:line.attempts()>0?ZzalStageSpeech.Status.UNCERTAIN:ZzalStageSpeech.Status.FAILED;
   row.complete(claim.token(),status,line.text(),LineChain.FIXED.equals(line.generator())?"FALLBACK":"LLM",line.motion(),null,json.writeValueAsString(line),line.failReason(),now);
  }
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public ZzalStageSpeech.View activate(Long owner,Long petId,ConversationScene.Step step,ConversationScene.Beat phase,Instant now){
  var pet=pets.findByIdForUpdate(petId).filter(p->p.isOwnedBy(owner)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
  var found=rows.findByPetIdAndIntroVersionAndStepAndPhase(petId,4,step,phase);
  // Older per-scene caches are not regenerated automatically and remain a free UI fallback.
  if(found.isEmpty()){
   if(!validPosition(pet,step,phase))throw new BusinessException(ErrorCode.ZZAL_TUTORIAL_STEP_MISMATCH);
   var context=chat.tutorialStageContext(pet,step,phase,now);String snapshot=json.writeValueAsString(context);
   var session=sessions.findByPetIdAndDayOfAndSlot(petId,AwakeClock.dateOf(pet.getHatchedAt()),ChatSlot.CONTINUOUS).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_CHAT_SESSION_STALE));
   var unavailable=rows.saveAndFlush(new ZzalStageSpeech(owner,petId,4,step,phase,position(pet,step),session.getId(),snapshot,sha(snapshot),revision(pet),now));
   unavailable.complete(unavailable.token(),ZzalStageSpeech.Status.FAILED,null,"FALLBACK",null,null,null,"batch_unavailable",now);
   return stageView(pet,unavailable);
  }
  var row=rows.lockById(found.get().id()).orElseThrow();boolean current=visible(pet,row);
  if(current&&row.status()==ZzalStageSpeech.Status.READY&&row.turnRef()==null){
   var generated=json.readValue(row.costTrace(),GeneratedLine.class);
   var session=sessions.findById(row.sessionId()).filter(s->s.getPetId().equals(petId)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_CHAT_SESSION_STALE));
   var previous=turns.findTop40BySessionIdOrderByIdxDesc(session.getId());int index=previous.isEmpty()?0:previous.getFirst().getIdx()+1;
   var context=chat.tutorialStageContext(pet,step,phase,now);
   String motion=com.lore.zzal.attendance.CustomChatActions.select(generated.motion(),context.actionOptions());
   var turn=turns.save(ZzalChatTurn.pet(session,index,TurnType.CONTINUE,row.line(),motion,generated.generator(),generated.failReason(),null,generated.outcome().code(),generated.latencyMs(),pet.now(now)));
   // Batch prompts use the authored sheet and character state, never retrieved memory facts.
   turn.recordMemoryContext(List.of());
   session.notePetTurn(generated.generator(),generated.costUsd());row.activate(turn.getId(),motion,now);
  }
  return stageView(pet,row);
 }
 private Claim claimScene(Long owner,Long petId,ConversationScene.Step step,ConversationScene.Beat phase,Instant realNow,String language){
  if(step==null||phase==null)throw new BusinessException(ErrorCode.ZZAL_TUTORIAL_STEP_MISMATCH);
  ZzalPet pet=petService.awake(owner,petId,realNow);
  if(!validPosition(pet,step,phase))throw new BusinessException(ErrorCode.ZZAL_TUTORIAL_STEP_MISMATCH);
  if(discovery(step)&&language!=null)pet.diaryLanguage(language);
  var existing=rows.findByPetIdAndIntroVersionAndStepAndPhase(petId,4,step,phase);
  if(existing.isPresent()){var row=existing.get();return new Claim(false,row.id(),row.token(),null,discovery(step)?row.view(visible(pet,row)):stageView(pet,row));}
  var context=discovery(step)?chat.firstMeetingDiscoveryContext(pet,realNow):chat.tutorialStageContext(pet,step,phase,realNow);
  var session=sessions.findByPetIdAndDayOfAndSlot(petId,AwakeClock.dateOf(pet.getHatchedAt()),ChatSlot.CONTINUOUS)
    .orElseThrow(()->new BusinessException(ErrorCode.ZZAL_CHAT_SESSION_STALE));
  String snapshot=json.writeValueAsString(context);
  var row=rows.saveAndFlush(new ZzalStageSpeech(owner,petId,4,step,phase,position(pet,step),session.getId(),snapshot,sha(snapshot),revision(pet,step),realNow));
  return new Claim(true,row.id(),row.token(),context,row.view(true));
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public ZzalStageSpeech.View complete(Long owner,Long petId,Claim claim,GeneratedLine generated,Instant realNow){
  var pet=pets.findByIdForUpdate(petId).filter(p->p.isOwnedBy(owner)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
  var row=rows.lockById(claim.id()).filter(r->r.petId().equals(petId)&&r.ownerId().equals(owner)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
  if(row.status()!=ZzalStageSpeech.Status.PENDING||!row.token().equals(claim.token()))return row.view(visible(pet,row));
  if(!row.hash().equals(sha(json.writeValueAsString(claim.context()))))throw new IllegalStateException("Stage context differs from frozen snapshot");
  boolean current=visible(pet,row);boolean success=Set.of("ok","retried_ok","truncated").contains(generated.outcome().code());
  String source=LineChain.FIXED.equals(generated.generator())?"FALLBACK":"LLM";
  var cost=new LinkedHashMap<String,Object>();cost.put("model",generated.model());cost.put("generator",generated.generator());cost.put("reportedCostUsd",generated.costUsd());cost.put("attempts",generated.attempts());cost.put("outcome",generated.outcome().code());cost.put("failureReason",generated.failReason());cost.put("latencyMs",generated.latencyMs());cost.put("providerRequestId",null);cost.put("usageVerified",false);
  Long turnId=null;String motion="base";
  if(current&&!discovery(row.step())&&(!TutorialEndingSpeech.supports(row.step())||success&&!LineChain.FIXED.equals(generated.generator()))&&generated.text()!=null&&!generated.text().isBlank()){
   var session=sessions.findById(row.sessionId()).filter(s->s.getPetId().equals(petId)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_CHAT_SESSION_STALE));
   var previous=turns.findTop40BySessionIdOrderByIdxDesc(session.getId());int idx=previous.isEmpty()?0:previous.getFirst().getIdx()+1;
   var fresh=chat.tutorialStageContext(pet,row.step(),row.phase(),realNow);
   motion=com.lore.zzal.attendance.CustomChatActions.select(generated.motion(),fresh.actionOptions());
   var turn=turns.save(ZzalChatTurn.pet(session,idx,TurnType.CONTINUE,generated.text(),motion,generated.generator(),generated.failReason(),null,generated.outcome().code(),generated.latencyMs(),pet.now(realNow)));
   turn.recordMemoryContext(claim.context().factMemories().stream().map(com.lore.zzal.chat.memory.facts.FactView::id).toList());turnId=turn.getId();session.notePetTurn(generated.generator(),generated.costUsd());
  }
  var state=!current?ZzalStageSpeech.Status.SUPERSEDED:success?ZzalStageSpeech.Status.READY:generated.attempts()>0?ZzalStageSpeech.Status.UNCERTAIN:ZzalStageSpeech.Status.FAILED;
  row.complete(claim.token(),state,generated.text(),source,motion,turnId,json.writeValueAsString(cost),generated.failReason(),realNow);
  return stageView(pet,row);
 }
 private ZzalStageSpeech.View stageView(ZzalPet pet,ZzalStageSpeech row){
  if(discovery(row.step()))return row.view(visible(pet,row));
  boolean current=validPosition(pet,row.step(),row.phase());
  var view=row.view(visible(pet,row),row.turnRef()!=null);
  if(!TutorialEndingSpeech.supports(row.step())||!current||row.status()==ZzalStageSpeech.Status.PENDING)return view;
  if(view.status()==ZzalStageSpeech.Status.READY&&!"FALLBACK".equals(view.source()))return view;
  // A failed/old cache never purchases another batch, nor pretends to be persona-generated.
  return new ZzalStageSpeech.View(view.id(),view.step(),view.phase(),view.status(),view.contextRevision(),
    TutorialEndingSpeech.fallback(row.step(),sheets.build(pet)),"FALLBACK","base",null,view.updatedAt());
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void uncertain(Long owner,Long petId,Claim claim,GeneratedLine generated,Instant now){
  pets.findByIdForUpdate(petId).filter(p->p.isOwnedBy(owner)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
  rows.lockById(claim.id()).filter(r->r.petId().equals(petId)&&r.ownerId().equals(owner)).ifPresent(r->{
    String trace=generated==null?null:json.writeValueAsString(generated);
    r.complete(claim.token(),ZzalStageSpeech.Status.UNCERTAIN,generated==null?null:generated.text(),generated==null?null:LineChain.FIXED.equals(generated.generator())?"FALLBACK":"LLM",null,null,trace,"unconfirmed_generation",now);
  });
 }
 @Transactional(readOnly=true)
 public ZzalStageSpeech.View readDiscovery(Long owner,Long petId){
  var pet=pets.findById(petId).filter(p->p.isOwnedBy(owner)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
  return rows.findByPetIdAndIntroVersionAndStepAndPhase(petId,4,ConversationScene.Step.FIRST_MEETING_DISCOVERY,ConversationScene.Beat.ENTRY)
   .map(row->row.view(visible(pet,row))).orElse(null);
 }
 @Transactional(readOnly=true)
 public List<ZzalStageSpeech.View> read(Long owner,Long petId){
  var pet=pets.findById(petId).filter(p->p.isOwnedBy(owner)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
  if(!pet.hasFirstIntro()||pet.getFirstIntroVersion()!=4||pet.isSleeping()||pet.isTraveling())return List.of();
  return rows.findByPetIdAndIntroVersion(petId,4).stream().filter(r->!discovery(r.step())&&validPosition(pet,r.step(),r.phase())).map(r->stageView(pet,r)).toList();
 }
}
