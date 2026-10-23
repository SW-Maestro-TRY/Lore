package com.lore.zzal.chat;
import com.lore.zzal.chat.stage.*;import com.lore.zzal.chat.prompt.ConversationScene;import com.lore.zzal.pet.ZzalPet;
import org.hibernate.*;import org.hibernate.cfg.Configuration;import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;import java.util.*;import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="LORE_STAGE_SPEECH_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55420/lore_stage_speech_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StageSpeechPostgresTest {
 SessionFactory sf;Long petId;final Instant now=Instant.parse("2026-10-18T03:00:00Z");
 @BeforeAll void schema(){
  sf=new Configuration().addAnnotatedClass(ZzalPet.class).addAnnotatedClass(ZzalStageSpeech.class).addAnnotatedClass(com.lore.zzal.chat.session.ZzalChatSession.class).addAnnotatedClass(com.lore.zzal.chat.session.ZzalChatTurn.class)
   .setProperty("hibernate.connection.url",System.getenv("LORE_STAGE_SPEECH_DB_URL"))
   .setProperty("hibernate.connection.username",System.getenv("LORE_TEST_DB_USERNAME"))
   .setProperty("hibernate.connection.password",System.getenv("LORE_TEST_DB_PASSWORD"))
   .setProperty("hibernate.hbm2ddl.auto","create").setProperty("hibernate.connection.pool_size","2").setProperty("hibernate.show_sql","false").buildSessionFactory();
  try(var session=sf.openSession()){session.doWork(c->{try(var sql=c.createStatement()){sql.execute("DROP TABLE zzal_stage_speech");}org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("db/migration/V20261018_2300__zzal_stage_speech.sql"));});}
 }
 @BeforeEach void seed(){try(var s=sf.openSession()){var t=s.beginTransaction();var p=ZzalPet.draft(4L,"existing-source",now);ReflectionTestUtils.setField(p,"createdAt",now);ReflectionTestUtils.setField(p,"updatedAt",now);s.persist(p);t.commit();petId=p.getId();}}
 @AfterAll void close(){if(sf!=null)sf.close();}
 ZzalStageSpeech row(){String snapshot="{\"original\":\"원문\"}";return new ZzalStageSpeech(4L,petId,4,ConversationScene.Step.FEED,ConversationScene.Beat.ENTRY,"FEED",1L,snapshot,StageSpeechTransactions.sha(snapshot),StageSpeechTransactions.sha("persona"),now);}
 @Test void frozenPendingRoundTripsAndReadRepositoryDoesNotGenerateOrMutate(){Long id;UUID token;try(var s=sf.openSession()){var t=s.beginTransaction();var row=row();s.persist(row);t.commit();id=row.id();token=row.token();}
  try(var s=sf.openSession()){var repo=new JpaRepositoryFactory(s).getRepository(ZzalStageSpeechRepository.class);var restored=repo.findByPetIdAndIntroVersionAndStepAndPhase(petId,4,ConversationScene.Step.FEED,ConversationScene.Beat.ENTRY).orElseThrow();assertThat(restored.id()).isEqualTo(id);assertThat(restored.token()).isEqualTo(token);assertThat(restored.status()).isEqualTo(ZzalStageSpeech.Status.PENDING);assertThat(StageSpeechTransactions.sha(restored.snapshot())).isEqualTo(restored.hash());assertThat(repo.findByPetIdAndIntroVersion(petId+999,4)).isEmpty();assertThat(s.find(ZzalPet.class,petId).getSourceImageKey()).isEqualTo("existing-source");}
 }
 @Test void uniqueSceneRejectsSecondClaimWithoutDeletingFirst(){try(var s=sf.openSession()){var t=s.beginTransaction();s.persist(row());t.commit();}
  assertThatThrownBy(()->{try(var s=sf.openSession()){var t=s.beginTransaction();s.persist(row());t.commit();}}).isInstanceOf(jakarta.persistence.PersistenceException.class);
  try(var s=sf.openSession()){var count=s.createQuery("select count(s) from ZzalStageSpeech s where s.petId=:pet",Long.class).setParameter("pet",petId).getSingleResult();assertThat(count).isEqualTo(1);}
 }
 @Test void claimTokenAndCompletedLineCostRemainDurable(){Long id;UUID token;try(var s=sf.openSession()){var t=s.beginTransaction();var row=row();s.persist(row);t.commit();id=row.id();token=row.token();}
  try(var s=sf.openSession()){var t=s.beginTransaction();var row=s.find(ZzalStageSpeech.class,id,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);row.complete(UUID.randomUUID(),ZzalStageSpeech.Status.READY,"wrong","LLM","base",1L,"{}",null,now);assertThat(row.status()).isEqualTo(ZzalStageSpeech.Status.PENDING);row.complete(token,ZzalStageSpeech.Status.READY,"저장원문","LLM","base",77L,"{\"reportedCostUsd\":0.001}",null,now.plusSeconds(1));t.commit();}
  try(var s=sf.openSession()){var row=s.find(ZzalStageSpeech.class,id);assertThat(row.view(true).line()).isEqualTo("저장원문");assertThat(row.view(true).turnRef()).isEqualTo(77L);assertThat(row.view(false).line()).isNull();row.complete(token,ZzalStageSpeech.Status.READY,"rewrite","LLM","base",78L,"{}",null,now.plusSeconds(2));assertThat(row.view(true).line()).isEqualTo("저장원문");}
 }
 @Test void actualJpaProxyCommitsClaimAndRunsOnlyOneModelOutsidePetLock()throws Exception{
  var manager=new org.springframework.orm.jpa.JpaTransactionManager(sf);
  var template=new org.springframework.transaction.support.TransactionTemplate(manager);
  var em=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(sf);
  Long sessionId=template.execute(status->{var p=em.find(ZzalPet.class,petId);p.character("범용아이","",null,"세계",null,null,now);p.markAlive("sheet","identity",now);p.beginFirstMeeting();p.advanceFirstMeeting(com.lore.zzal.pet.persona.FirstMeeting.State.COMPLETE);p.devSetFirstIntro(4,com.lore.zzal.pet.FirstIntro.Step.SNACK,now);p.rememberExplicitCallMe("작가님");var session=com.lore.zzal.chat.session.ZzalChatSession.open(petId,com.lore.zzal.pet.AwakeClock.dateOf(p.getHatchedAt()),ChatSlot.CONTINUOUS,com.lore.zzal.chat.session.SessionKind.BABY,now,null,now);em.persist(session);em.flush();return session.getId();});
  var pets=org.mockito.Mockito.mock(com.lore.zzal.pet.ZzalPetRepository.class);
  org.mockito.Mockito.when(pets.findByIdForUpdate(petId)).thenAnswer(inv->Optional.ofNullable(em.find(ZzalPet.class,petId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)));
  org.mockito.Mockito.when(pets.findById(petId)).thenAnswer(inv->Optional.ofNullable(em.find(ZzalPet.class,petId)));
  var petService=org.mockito.Mockito.mock(com.lore.zzal.pet.PetService.class);
  org.mockito.Mockito.when(petService.awake(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{var p=em.find(ZzalPet.class,petId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);if(!p.isOwnedBy(inv.getArgument(0))||p.isSleeping()||p.isTraveling())throw new IllegalStateException("blocked");return p;});
  var sessions=org.mockito.Mockito.mock(com.lore.zzal.chat.session.ZzalChatSessionRepository.class);
  org.mockito.Mockito.when(sessions.findByPetIdAndDayOfAndSlot(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(inv->Optional.ofNullable(em.find(com.lore.zzal.chat.session.ZzalChatSession.class,sessionId)));
  org.mockito.Mockito.when(sessions.findById(sessionId)).thenAnswer(inv->Optional.ofNullable(em.find(com.lore.zzal.chat.session.ZzalChatSession.class,sessionId)));
  var turns=org.mockito.Mockito.mock(com.lore.zzal.chat.session.ZzalChatTurnRepository.class);
  org.mockito.Mockito.when(turns.findTop40BySessionIdOrderByIdxDesc(sessionId)).thenAnswer(inv->em.createQuery("select t from ZzalChatTurn t where t.sessionId=:sid order by t.idx desc",com.lore.zzal.chat.session.ZzalChatTurn.class).setParameter("sid",sessionId).setMaxResults(40).getResultList());
  org.mockito.Mockito.when(turns.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{com.lore.zzal.chat.session.ZzalChatTurn turn=inv.getArgument(0);em.persist(turn);em.flush();return turn;});
  var sheets=new com.lore.zzal.chat.persona.PersonaSheetBuilder(null);var chat=org.mockito.Mockito.mock(ChatService.class);
  org.mockito.Mockito.when(chat.tutorialStageContext(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{ZzalPet p=inv.getArgument(0);return new com.lore.zzal.chat.prompt.ChatContext(petId,sheets.build(p),com.lore.zzal.chat.prompt.PetState.of(p,now),com.lore.zzal.chat.session.SessionKind.BABY,new com.lore.zzal.chat.session.TurnPlan(com.lore.zzal.chat.session.TurnType.CONTINUE,2,false,null,false,ChatSlot.CONTINUOUS),null,List.of(),List.of("base")).withScene(new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE,inv.getArgument(1),inv.getArgument(2)));});
  var rows=new JpaRepositoryFactory(em).getRepository(ZzalStageSpeechRepository.class);
  var target=new StageSpeechTransactions(rows,pets,petService,chat,sheets,sessions,turns,org.mockito.Mockito.mock(com.lore.zzal.chat.delivery.ZzalChatDeliveryRepository.class),tools.jackson.databind.json.JsonMapper.builder().build());
  var factory=new org.springframework.aop.framework.ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();
  com.lore.zzal.chat.line.ChatLineClient spy=(system,input,model,timeout)->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();calls.incrementAndGet();entered.countDown();if(!release.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("latch");return new com.lore.zzal.chat.line.ChatLineClient.Completion("{\"line\":\"간식이 당기네.\",\"motion\":\"base\"}",java.math.BigDecimal.ZERO,0,0);};
  var chain=new com.lore.zzal.chat.line.LineChain(new com.lore.zzal.chat.line.LlmLineGenerator(spy,"spy-only",java.time.Duration.ofSeconds(1),new com.lore.zzal.chat.prompt.SystemPromptCache()),null);
  var service=new StageSpeechService((StageSpeechTransactions)factory.getProxy(),chain);var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);var completions=new java.util.concurrent.ExecutorCompletionService<ZzalStageSpeech.View>(executor);
  try{for(int i=0;i<2;i++)completions.submit(()->{start.await();return service.prepare(4L,petId,ConversationScene.Step.SNACK,ConversationScene.Beat.ENTRY,now);});start.countDown();assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   var pending=completions.poll(5,java.util.concurrent.TimeUnit.SECONDS).get();assertThat(pending.status()).isEqualTo(ZzalStageSpeech.Status.PENDING);assertThat(calls.get()).isEqualTo(1);
   release.countDown();assertThat(completions.poll(8,java.util.concurrent.TimeUnit.SECONDS).get().status()).isEqualTo(ZzalStageSpeech.Status.READY);assertThat(service.read(4L,petId)).hasSize(1);assertThat(calls.get()).isEqualTo(1);
   template.execute(status->{assertThat(em.find(com.lore.zzal.chat.session.ZzalChatSession.class,sessionId).getRoundCount()).isZero();assertThat(em.createQuery("select count(t) from ZzalChatTurn t where t.petId=:pet and t.speaker=:speaker",Long.class).setParameter("pet",petId).setParameter("speaker",com.lore.zzal.chat.session.Speaker.USER).getSingleResult()).isZero();assertThat(em.createQuery("select count(s) from ZzalStageSpeech s where s.petId=:pet",Long.class).setParameter("pet",petId).getSingleResult()).isEqualTo(1);return null;});
  }finally{release.countDown();executor.shutdownNow();}
 }

}
