package com.lore.zzal.chat;
import com.lore.zzal.chat.stage.*;import com.lore.zzal.chat.line.*;import com.lore.zzal.chat.prompt.*;import com.lore.zzal.chat.persona.*;import com.lore.zzal.chat.session.*;import com.lore.zzal.chat.memory.*;import com.lore.zzal.chat.delivery.*;
import com.lore.zzal.pet.*;import com.lore.zzal.pet.persona.*;import com.lore.zzal.motion.MotionCatalog;
import org.junit.jupiter.api.*;import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;import org.springframework.transaction.*;import org.springframework.transaction.support.*;import org.springframework.transaction.interceptor.*;import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;import java.util.*;import java.util.concurrent.*;import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
class FirstMeetingDiscoveryFlowTest {
 final Instant now=Instant.parse("2026-10-18T03:00:00Z");ZzalPet pet;ChatStores st;StageSpeechService service;StageSpeechTransactions tx;ChatService chat;
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
  var sheets=new PersonaSheetBuilder(null);chat=new ChatService(st.callRepo,st.sessionRepo,st.turnRepo,petService,new MotionCatalog("","","v1"),com.lore.zzal.PieceFixture.inMemory(new HashMap<>()),sheets,new RecentDaysMemory(st.turnRepo,st.sessionRepo),chain,5);
  pet.advanceFirstMeeting(FirstMeeting.State.DISCOVERED);
  ReflectionTestUtils.setField(pet,"callMe",null);ReflectionTestUtils.setField(pet,"callMeSource",null);
  when(st.turnRepo.existsBySessionId(any())).thenAnswer(inv->!st.turns.isEmpty());
  when(st.turnRepo.findFirstBySessionIdAndIdx(any(),anyInt())).thenAnswer(inv->st.turns.stream().filter(t->t.getSessionId().equals(inv.getArgument(0))&&t.getIdx()==(int)inv.getArgument(1)).findFirst());
  var rows=mock(ZzalStageSpeechRepository.class);
  when(rows.findByPetIdAndIntroVersionAndStepAndPhase(any(),anyInt(),any(),any())).thenAnswer(inv->stored.values().stream().filter(r->r.petId().equals(inv.getArgument(0))&&r.step()==inv.getArgument(2)&&r.phase()==inv.getArgument(3)).findFirst());
  when(rows.saveAndFlush(any())).thenAnswer(inv->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();ZzalStageSpeech row=inv.getArgument(0);ReflectionTestUtils.setField(row,"id",(long)stored.size()+1);stored.put(row.id(),row);return row;});
  when(rows.lockById(any())).thenAnswer(inv->Optional.ofNullable(stored.get(inv.getArgument(0))));when(rows.findByPetIdAndIntroVersion(any(),anyInt())).thenAnswer(inv->List.copyOf(stored.values()));
  var delivery=mock(ZzalChatDeliveryRepository.class);deliveryRepository=delivery;
  var target=new StageSpeechTransactions(rows,pets,petService,chat,sheets,st.sessionRepo,st.turnRepo,delivery,JsonMapper.builder().build());
  var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));tx=(StageSpeechTransactions)factory.getProxy();service=new StageSpeechService(tx,chain);
 }
 ZzalStageSpeech.View prepare(){return service.prepareDiscovery(1L,9L,now,"ja");}
 @Test void discoveryUsesSharedSixFieldsAndLanguageWithoutAnyChatTurn(){
  var view=prepare();assertThat(view.status()).isEqualTo(ZzalStageSpeech.Status.READY);assertThat(view.turnRef()).isNull();assertThat(view.step().name()).hasSize(23);
  for(String field:List.of("world","job","persona","likes","dislikes","extra"))assertThat(prompts.getFirst()).contains("원문-"+field);
  assertThat(prompts.getFirst()).contains("FIRST_MEETING_DISCOVERY","기본 언어: 일본어","아직 호칭은 확정되지 않았다");
  assertThat(st.turns).isEmpty();assertThat(st.sessions.getFirst().getRoundCount()).isZero();assertThat(pet.getFirstMeetingState()).isEqualTo(FirstMeeting.State.DISCOVERED);
  assertThat(prepare().id()).isEqualTo(view.id());assertThat(tx.readDiscovery(1L,9L).line()).isEqualTo(view.line());assertThat(calls).isEqualTo(1);assertThat(service.read(1L,9L)).isEmpty();
 }
 @Test void nextDuringCallSupersedesDiscoveryAndNeverStoresIdxZero(){duringCall=()->pet.advanceFirstMeeting(FirstMeeting.State.ADDRESS);var v=prepare();assertThat(v.status()).isEqualTo(ZzalStageSpeech.Status.SUPERSEDED);assertThat(v.line()).isNull();assertThat(st.turns).isEmpty();}
 @Test void confirmedAddressDuringCallSupersedesWithoutInventedUser(){duringCall=()->{pet.rememberExplicitCallMe("별님");pet.advanceFirstMeeting(FirstMeeting.State.READY_TO_ENTER);};assertThat(prepare().line()).isNull();assertThat(st.turns).isEmpty();}
 @Test void discoveryLeavesLegacyGreetingIdxZeroAvailable(){prepare();pet.advanceFirstMeeting(FirstMeeting.State.READY_TO_ENTER);pet.rememberExplicitCallMe("별님");chat.prepareFirstMeetingGreeting(pet,now);assertThat(st.turns).hasSize(1);assertThat(st.turns.getFirst().getIdx()).isZero();assertThat(st.turns.getFirst().getTurnType()).isEqualTo(TurnType.FIRST_MEET);assertThat(chat.firstMeetingGreeting(pet)).isNotBlank();assertThat(st.sessions.getFirst().getRoundCount()).isZero();assertThat(calls).isEqualTo(2);chat.prepareFirstMeetingGreeting(pet,now);assertThat(calls).isEqualTo(2);}
 @Test void overlapReturnsPendingAndCallsOnlyOnce()throws Exception{var entered=new CountDownLatch(1);var release=new CountDownLatch(1);duringCall=()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}};var executor=Executors.newSingleThreadExecutor();try{var first=executor.submit(this::prepare);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(prepare().status()).isEqualTo(ZzalStageSpeech.Status.PENDING);release.countDown();assertThat(first.get(5,TimeUnit.SECONDS).status()).isEqualTo(ZzalStageSpeech.Status.READY);assertThat(calls).isEqualTo(1);assertThat(st.turns).isEmpty();}finally{release.countDown();executor.shutdownNow();}}
 @Test void ownerSleepAndWrongPurposeNeverClaim(){assertThatThrownBy(()->service.prepareDiscovery(99L,9L,now,"ja")).isInstanceOf(com.lore.common.exception.BusinessException.class);assertThatThrownBy(()->service.prepare(1L,9L,ConversationScene.Step.FIRST_MEETING_DISCOVERY,ConversationScene.Beat.ENTRY,now)).isInstanceOf(com.lore.common.exception.BusinessException.class);ReflectionTestUtils.setField(pet,"sleepKind",SleepKind.NIGHT);assertThatThrownBy(this::prepare).isInstanceOf(com.lore.common.exception.BusinessException.class);assertThat(calls).isZero();assertThat(stored).isEmpty();}
 @Test void existingPostPreparesAndGetOnlyProjectsStoredSource(){
  var petApi=mock(PetService.class);when(petApi.get(1L,9L)).thenReturn(pet);var repository=mock(ZzalPetRepository.class);
  var meeting=new FirstMeetingService(petApi,repository,chat);ReflectionTestUtils.setField(meeting,"stageReads",tx);
  var controller=new PetController(petApi,new MotionCatalog("","","v1"),null,null,null,null);ReflectionTestUtils.setField(controller,"stageSpeeches",service);ReflectionTestUtils.setField(controller,"firstMeetings",meeting);
  var response=controller.firstMeeting(1L,9L,new FirstMeeting.Request(FirstMeeting.Action.PREPARE_DISCOVERY,null),"ja").getData();
  assertThat(response.discoveryStatus()).isEqualTo("READY");assertThat(response.discoverySource()).isEqualTo("LLM");assertThat(response.discoveryLine()).isEqualTo("간식이 조금 당기네.");
  assertThat(controller.firstMeeting(1L,9L).getData().discoveryLine()).isEqualTo(response.discoveryLine());assertThat(calls).isEqualTo(1);verify(petApi,never()).firstMeeting(any(),any(),any());assertThat(st.turns).isEmpty();
 }
 @Test void changedPersonaNeverDisplaysLateDiscovery(){duringCall=()->pet.recordAuthoredPersona(new AuthoredPersona(2,List.of(new AuthoredPersona.Field("world",List.of(),"수정 원문",false))));assertThat(prepare().status()).isEqualTo(ZzalStageSpeech.Status.SUPERSEDED);assertThat(st.turns).isEmpty();}
 @Test void uncertainDiscoveryIsNotAutomaticallyRegenerated(){fail=true;var first=prepare();assertThat(first.status()).isEqualTo(ZzalStageSpeech.Status.UNCERTAIN);assertThat(prepare().id()).isEqualTo(first.id());assertThat(calls).isEqualTo(1);assertThat(st.turns).isEmpty();}
 static class SimpleTransactions extends AbstractPlatformTransactionManager {int commits;protected Object doGetTransaction(){return new Object();}protected void doBegin(Object tx,TransactionDefinition def){}protected void doCommit(DefaultTransactionStatus status){commits++;}protected void doRollback(DefaultTransactionStatus status){}}
}
