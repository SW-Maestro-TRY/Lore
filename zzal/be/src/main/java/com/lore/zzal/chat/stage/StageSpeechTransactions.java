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
 private String revision(ZzalPet pet){return sha(json.writeValueAsString(sheets.build(pet)));}
 private boolean discovery(ConversationScene.Step step){return step==ConversationScene.Step.FIRST_MEETING_DISCOVERY;}
 private boolean discoveryPosition(ZzalPet pet){return pet.isAlive()&&!pet.isSleeping()&&!pet.isTraveling()&&
   pet.getFirstMeetingState()==com.lore.zzal.pet.persona.FirstMeeting.State.DISCOVERED;}
 private String position(ZzalPet pet,ConversationScene.Step step){return discovery(step)?"FIRST_MEETING":currentStep(pet);}
 private String currentStep(ZzalPet pet){return FirstIntro.sequence(pet.getFirstIntroVersion()).get(pet.getFirstIntroStep()).name();}
 private boolean validPosition(ZzalPet pet,ConversationScene.Step step,ConversationScene.Beat phase){
  if(discovery(step))return phase==ConversationScene.Beat.ENTRY&&discoveryPosition(pet);
  if(!pet.isAlive()||!pet.isFirstMeetingComplete()||pet.getFirstIntroVersion()!=4||!pet.hasFirstIntro()||pet.isSleeping()||pet.isTraveling())return false;
  int i=pet.firstIntroIndex(FirstIntro.Step.valueOf(step.name()));int expected=phase==ConversationScene.Beat.ENTRY?i:i+1;
  if(i<0||pet.getFirstIntroStep()!=expected)return false;
  // A late initial question must not replace the saved answer while the action is still pending.
  if(phase==ConversationScene.Beat.ENTRY&&(step==ConversationScene.Step.FEED||step==ConversationScene.Step.GAME)
      &&!deliveries.latestTutorial(pet.getId(),ChatSlot.CONTINUOUS.name(),step.name(),org.springframework.data.domain.PageRequest.of(0,1)).isEmpty())return false;
  return true;
 }
 private boolean visible(ZzalPet pet,ZzalStageSpeech row){return validPosition(pet,row.step(),row.phase())&&row.expectedStep().equals(position(pet,row.step()))&&row.revision().equals(revision(pet));}
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public Claim claim(Long owner,Long petId,ConversationScene.Step step,ConversationScene.Beat phase,Instant realNow){
  if(discovery(step))throw new BusinessException(ErrorCode.ZZAL_TUTORIAL_STEP_MISMATCH);
  return claimScene(owner,petId,step,phase,realNow,null);
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public Claim claimDiscovery(Long owner,Long petId,Instant realNow,String language){
  return claimScene(owner,petId,ConversationScene.Step.FIRST_MEETING_DISCOVERY,ConversationScene.Beat.ENTRY,realNow,language);
 }
 private Claim claimScene(Long owner,Long petId,ConversationScene.Step step,ConversationScene.Beat phase,Instant realNow,String language){
  if(step==null||phase==null)throw new BusinessException(ErrorCode.ZZAL_TUTORIAL_STEP_MISMATCH);
  ZzalPet pet=petService.awake(owner,petId,realNow);
  if(!validPosition(pet,step,phase))throw new BusinessException(ErrorCode.ZZAL_TUTORIAL_STEP_MISMATCH);
  if(discovery(step)&&language!=null)pet.diaryLanguage(language);
  var existing=rows.findByPetIdAndIntroVersionAndStepAndPhase(petId,4,step,phase);
  if(existing.isPresent()){var row=existing.get();return new Claim(false,row.id(),row.token(),null,row.view(visible(pet,row)));}
  var context=discovery(step)?chat.firstMeetingDiscoveryContext(pet,realNow):chat.tutorialStageContext(pet,step,phase,realNow);
  var session=sessions.findByPetIdAndDayOfAndSlot(petId,AwakeClock.dateOf(pet.getHatchedAt()),ChatSlot.CONTINUOUS)
    .orElseThrow(()->new BusinessException(ErrorCode.ZZAL_CHAT_SESSION_STALE));
  String snapshot=json.writeValueAsString(context);
  var row=rows.saveAndFlush(new ZzalStageSpeech(owner,petId,4,step,phase,position(pet,step),session.getId(),snapshot,sha(snapshot),revision(pet),realNow));
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
  if(current&&!discovery(row.step())&&generated.text()!=null&&!generated.text().isBlank()){
   var session=sessions.findById(row.sessionId()).filter(s->s.getPetId().equals(petId)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_CHAT_SESSION_STALE));
   var previous=turns.findTop40BySessionIdOrderByIdxDesc(session.getId());int idx=previous.isEmpty()?0:previous.getFirst().getIdx()+1;
   var fresh=chat.tutorialStageContext(pet,row.step(),row.phase(),realNow);
   motion=com.lore.zzal.attendance.CustomChatActions.select(generated.motion(),fresh.actionOptions());
   var turn=turns.save(ZzalChatTurn.pet(session,idx,TurnType.CONTINUE,generated.text(),motion,generated.generator(),generated.failReason(),null,generated.outcome().code(),generated.latencyMs(),pet.now(realNow)));
   turn.recordMemoryContext(claim.context().factMemories().stream().map(com.lore.zzal.chat.memory.facts.FactView::id).toList());turnId=turn.getId();session.notePetTurn(generated.generator(),generated.costUsd());
  }
  var state=!current?ZzalStageSpeech.Status.SUPERSEDED:success?ZzalStageSpeech.Status.READY:generated.attempts()>0?ZzalStageSpeech.Status.UNCERTAIN:ZzalStageSpeech.Status.FAILED;
  row.complete(claim.token(),state,generated.text(),source,motion,turnId,json.writeValueAsString(cost),generated.failReason(),realNow);
  return row.view(current);
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
  return rows.findByPetIdAndIntroVersion(petId,4).stream().filter(r->!discovery(r.step())&&validPosition(pet,r.step(),r.phase())).map(r->r.view(visible(pet,r))).toList();
 }
}
