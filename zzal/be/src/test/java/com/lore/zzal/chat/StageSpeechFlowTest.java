package com.lore.zzal.chat;
import com.lore.zzal.chat.stage.*;import com.lore.zzal.chat.line.*;import com.lore.zzal.chat.prompt.*;import com.lore.zzal.chat.persona.*;import com.lore.zzal.chat.session.*;import com.lore.zzal.chat.memory.*;import com.lore.zzal.chat.delivery.*;
import com.lore.zzal.pet.*;import com.lore.zzal.pet.persona.*;import com.lore.zzal.motion.MotionCatalog;
import org.junit.jupiter.api.*;import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;import org.springframework.transaction.*;import org.springframework.transaction.support.*;import org.springframework.transaction.interceptor.*;import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;import java.util.*;import java.util.concurrent.*;import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
class StageSpeechFlowTest {
 final Instant now=Instant.parse("2026-10-18T03:00:00Z");ZzalPet pet;ChatStores st;StageSpeechService service;StageSpeechTransactions tx;
 ZzalChatDeliveryRepository deliveryRepository;
 final Map<Long,ZzalStageSpeech> stored=new ConcurrentHashMap<>();int calls;Runnable duringCall=()->{};boolean fail;final List<String> prompts=new ArrayList<>();SimpleTransactions manager=new SimpleTransactions();
 @BeforeEach void setup(){
  stored.clear();st=new ChatStores();pet=ZzalPet.draft(1L,"source",now.minusSeconds(60));pet.character("다른아이","",null,"다른세계",null,null,now.minusSeconds(60));ReflectionTestUtils.setField(pet,"id",9L);pet.markAlive("sheet","identity",now);
  pet.beginFirstMeeting();pet.advanceFirstMeeting(FirstMeeting.State.COMPLETE);pet.devSetFirstIntro(4,FirstIntro.Step.SNACK,now);pet.rememberExplicitCallMe("현재호칭");pet.diaryLanguage("ja");
  pet.recordAuthoredPersona(new AuthoredPersona(1,java.util.stream.Stream.of("world","job","persona","likes","dislikes","extra").map(id->new AuthoredPersona.Field(id,List.of(),"원문-"+id,false)).toList()));
  var session=st.sessionRepo.save(ZzalChatSession.open(9L,AwakeClock.dateOf(pet.getHatchedAt()),ChatSlot.CONTINUOUS,SessionKind.BABY,now,null,now));
  when(st.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
  when(st.turnRepo.findTop40BySessionIdOrderByIdxDesc(any())).thenAnswer(inv->st.turns.stream().filter(t->t.getSessionId().equals(inv.getArgument(0))).sorted(Comparator.comparingInt(ZzalChatTurn::getIdx).reversed()).limit(40).toList());
  when(st.turnRepo.findTop40ByPetIdAndCreatedAtBetweenOrderByCreatedAtDescIdDesc(any(),any(),any())).thenAnswer(inv->st.turns.stream().sorted(Comparator.comparing(ZzalChatTurn::getId).reversed()).limit(40).toList());
  var pets=mock(ZzalPetRepository.class);when(pets.findById(any())).thenReturn(Optional.of(pet));when(pets.findByIdForUpdate(any())).thenReturn(Optional.of(pet));
  var petService=mock(PetService.class);when(petService.awake(any(),any(),any())).thenAnswer(inv->{if(!pet.isOwnedBy(inv.getArgument(0)))throw new com.lore.common.exception.BusinessException(com.lore.common.exception.ErrorCode.ZZAL_PET_NOT_FOUND);if(pet.isSleeping())throw new com.lore.common.exception.BusinessException(com.lore.common.exception.ErrorCode.ZZAL_PET_SLEEPING);if(pet.isTraveling())throw new com.lore.common.exception.BusinessException(com.lore.common.exception.ErrorCode.ZZAL_TRAVELING);return pet;});
  ChatLineClient spy=(system,input,model,timeout)->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();calls++;prompts.add(system+"\n"+input);duringCall.run();if(fail)throw new java.util.concurrent.TimeoutException("synthetic");return new ChatLineClient.Completion("{\"line\":\"간식이 조금 당기네.\",\"motion\":\"base\",\"memory_deltas\":[]}",BigDecimal.ZERO,0,0);};
  var chain=new LineChain(new LlmLineGenerator(spy,"spy-only",Duration.ofMillis(100),new SystemPromptCache()),null);
  var sheets=new PersonaSheetBuilder(null);var chat=new ChatService(st.callRepo,st.sessionRepo,st.turnRepo,petService,new MotionCatalog("","","v1"),com.lore.zzal.PieceFixture.inMemory(new HashMap<>()),sheets,new RecentDaysMemory(st.turnRepo,st.sessionRepo),chain,5);
  var rows=mock(ZzalStageSpeechRepository.class);
  when(rows.findByPetIdAndIntroVersionAndStepAndPhase(any(),anyInt(),any(),any())).thenAnswer(inv->stored.values().stream().filter(r->r.petId().equals(inv.getArgument(0))&&r.step()==inv.getArgument(2)&&r.phase()==inv.getArgument(3)).findFirst());
  when(rows.saveAndFlush(any())).thenAnswer(inv->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();ZzalStageSpeech row=inv.getArgument(0);ReflectionTestUtils.setField(row,"id",(long)stored.size()+1);stored.put(row.id(),row);return row;});
  when(rows.lockById(any())).thenAnswer(inv->Optional.ofNullable(stored.get(inv.getArgument(0))));when(rows.findByPetIdAndIntroVersion(any(),anyInt())).thenAnswer(inv->List.copyOf(stored.values()));
  var delivery=mock(ZzalChatDeliveryRepository.class);deliveryRepository=delivery;
  var target=new StageSpeechTransactions(rows,pets,petService,chat,sheets,st.sessionRepo,st.turnRepo,delivery,JsonMapper.builder().build());
  var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));tx=(StageSpeechTransactions)factory.getProxy();service=new StageSpeechService(tx,chain);
 }
 ZzalStageSpeech.View prepare(){return service.prepare(1L,9L,ConversationScene.Step.SNACK,ConversationScene.Beat.ENTRY,now);}
 @Test void committedClaimRealPromptAndPetOnlyTurnAreReusedByPrepareAndGet(){
  int round=st.sessions.getFirst().getRoundCount();var view=prepare();assertThat(view.status()).isEqualTo(ZzalStageSpeech.Status.READY);assertThat(view.source()).isEqualTo("LLM");assertThat(calls).isEqualTo(1);assertThat(manager.commits).isGreaterThanOrEqualTo(2);
  for(String field:List.of("world","job","persona","likes","dislikes","extra"))assertThat(prompts.getFirst()).contains("원문-"+field);
  assertThat(prompts.getFirst()).contains("현재호칭","기본 언어: 일본어","TUTORIAL_STAGE");
  assertThat(st.turns).hasSize(1).allSatisfy(turn->assertThat(turn.getSpeaker()).isEqualTo(Speaker.PET));assertThat(st.sessions.getFirst().getRoundCount()).isEqualTo(round);assertThat(st.sessions.getFirst().isRewardGiven()).isFalse();assertThat(pet.introAt(FirstIntro.Step.SNACK)).isTrue();assertThat(pet.careMotionCursor("PET")).isNull();
  assertThat(prepare().id()).isEqualTo(view.id());assertThat(service.read(1L,9L)).hasSize(1);assertThat(calls).isEqualTo(1);assertThat(st.turns).hasSize(1);
 }
 @Test void lateStepOrPersonaRevisionDoesNotShowOrStoreNewTurn(){duringCall=()->pet.advanceFirstIntro(FirstIntro.Step.SNACK);assertThat(prepare().status()).isEqualTo(ZzalStageSpeech.Status.SUPERSEDED);assertThat(st.turns).isEmpty();assertThat(service.read(1L,9L)).isEmpty();}
 @Test void latePersonaRevisionIsNotDisplayed(){duringCall=()->pet.rememberExplicitCallMe("새호칭");var result=prepare();assertThat(result.status()).isEqualTo(ZzalStageSpeech.Status.SUPERSEDED);assertThat(result.line()).isNull();assertThat(st.turns).isEmpty();}
 @Test void lateSleepSuppressesSpeechAndGet(){duringCall=()->{ReflectionTestUtils.setField(pet,"sleepKind",SleepKind.NIGHT);ReflectionTestUtils.setField(pet,"sleptAt",now);};assertThat(prepare().line()).isNull();assertThat(st.turns).isEmpty();assertThat(service.read(1L,9L)).isEmpty();}
 @Test void timeoutIsOneAttemptAndUncertainClaimNeverAutoRetries(){fail=true;var result=prepare();assertThat(result.status()).isEqualTo(ZzalStageSpeech.Status.UNCERTAIN);assertThat(result.source()).isEqualTo("FALLBACK");prepare();assertThat(calls).isEqualTo(1);assertThat(st.turns).hasSize(1).allSatisfy(t->assertThat(t.getSpeaker()).isEqualTo(Speaker.PET));}
 @Test void pendingSecondRequestDoesNotGenerateWhileFirstIsOutsideTransaction()throws Exception{var entered=new CountDownLatch(1);var release=new CountDownLatch(1);duringCall=()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}};var executor=Executors.newSingleThreadExecutor();try{var first=executor.submit(this::prepare);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(prepare().status()).isEqualTo(ZzalStageSpeech.Status.PENDING);release.countDown();assertThat(first.get(5,TimeUnit.SECONDS).status()).isEqualTo(ZzalStageSpeech.Status.READY);assertThat(calls).isEqualTo(1);}finally{release.countDown();executor.shutdownNow();}}
 @Test void ownerStepAndCallerTransactionAreRejectedBeforeAnyModel(){assertThatThrownBy(()->service.prepare(999L,9L,ConversationScene.Step.SNACK,ConversationScene.Beat.ENTRY,now)).isInstanceOf(com.lore.common.exception.BusinessException.class);assertThatThrownBy(()->service.prepare(1L,9L,ConversationScene.Step.BATH,ConversationScene.Beat.ENTRY,now)).isInstanceOf(com.lore.common.exception.BusinessException.class);assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->prepare())).isInstanceOf(IllegalStateException.class);assertThat(calls).isZero();assertThat(stored).isEmpty();}
 @Test void afterActionUsesOnlyImmediatelyCompletedStepAndDoesNotAdvanceAgain(){
  var result=service.prepare(1L,9L,ConversationScene.Step.FEED,ConversationScene.Beat.AFTER_ACTION,now);
  assertThat(result.status()).isEqualTo(ZzalStageSpeech.Status.READY);assertThat(prompts.getFirst()).contains("AFTER_ACTION");assertThat(pet.introAt(FirstIntro.Step.SNACK)).isTrue();assertThat(st.turns).hasSize(1);assertThat(st.sessions.getFirst().getRoundCount()).isZero();
 }
 @Test void sleepingOrTravelingBeforePrepareDoesNotClaimOrGenerate(){
  ReflectionTestUtils.setField(pet,"sleepKind",SleepKind.NIGHT);assertThatThrownBy(this::prepare).isInstanceOf(com.lore.common.exception.BusinessException.class);ReflectionTestUtils.setField(pet,"sleepKind",null);ReflectionTestUtils.setField(pet,"tripStartedAt",now);assertThatThrownBy(this::prepare).isInstanceOf(com.lore.common.exception.BusinessException.class);assertThat(stored).isEmpty();assertThat(calls).isZero();
 }
 @Test void preparePurposeUsesExistingEndpointWithoutAcknowledgingOrAdvancing(){
  var request=new com.lore.zzal.pet.dto.PetRequests.TutorialSeen(FirstIntro.Step.SNACK,com.lore.zzal.pet.dto.PetRequests.TutorialSeen.TutorialPurpose.PREPARE_SPEECH,ConversationScene.Beat.ENTRY);
  assertThat(request.isPrepareValid()).isTrue();assertThat(new com.lore.zzal.pet.dto.PetRequests.TutorialSeen(FirstIntro.Step.ATTENDANCE_INTRO).isPrepareValid()).isTrue();
  assertThat(new com.lore.zzal.pet.dto.PetRequests.TutorialSeen(FirstIntro.Step.ALBUM,request.purpose(),request.phase()).isPrepareValid()).isFalse();
  var petService=mock(PetService.class);var controller=new PetController(petService,new MotionCatalog("","","v1"),null,null,null,null);ReflectionTestUtils.setField(controller,"stageSpeeches",service);
  var response=controller.tutorialSeen(1L,9L,request);assertThat(response.getData()).isInstanceOf(PetController.PreparedStage.class);
  verify(petService,never()).tutorialSeen(any(),any(),any(),any());assertThat(pet.introAt(FirstIntro.Step.SNACK)).isTrue();assertThat(calls).isEqualTo(1);
 }
 @Test void answeredQuestionEntryCannotClaimOrRegenerate(){
  pet.devSetFirstIntro(4,FirstIntro.Step.FEED,now);var request=new com.lore.zzal.chat.dto.ChatRequests.Answer("먹었어",st.sessions.getFirst().getId(),0,UUID.randomUUID(),new com.lore.zzal.chat.dto.ChatRequests.Answer.Tutorial(ConversationScene.Step.FEED,com.lore.zzal.chat.dto.ChatRequests.Answer.AnswerType.SUGGESTED));
  var receipt=new ZzalChatDelivery(9L,ChatSlot.CONTINUOUS,request,"{}",now);
  when(deliveryRepository.latestTutorial(any(),any(),eq("FEED"),any())).thenReturn(List.of(receipt));
  assertThatThrownBy(()->service.prepare(1L,9L,ConversationScene.Step.FEED,ConversationScene.Beat.ENTRY,now)).isInstanceOf(com.lore.common.exception.BusinessException.class);assertThat(stored).isEmpty();assertThat(calls).isZero();
 }
 static class SimpleTransactions extends AbstractPlatformTransactionManager {int commits;protected Object doGetTransaction(){return new Object();}protected void doBegin(Object tx,TransactionDefinition def){}protected void doCommit(DefaultTransactionStatus status){commits++;}protected void doRollback(DefaultTransactionStatus status){}}
}
