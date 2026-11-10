package com.lore.zzal.diary;

import com.lore.zzal.it.*;
import com.lore.zzal.pet.*;
import com.lore.zzal.chat.presence.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@ZzalIntegrationTest
@TestPropertySource(properties={"spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=create","zzal.diary.enabled=false"})
class DiaryProductIT extends ZzalItSupport {
    @Autowired DiaryService diary;
    @Autowired DiaryEntryRepository entries;
    @Autowired DiaryWorkerTransactions workers;
    @Autowired ChatPresenceService presence;
    private Instant t(){return LocalDateTime.of(2026,10,16,10,0).atZone(ZzalRules.ZONE).toInstant();}
    record Room(Long user,Long pet){}
    private Room room(){
        Long user=newUserId();Long pet=transactions.execute(s->{var p=ZzalPet.draft(user,newUploadedImageKey(user),t());
            p.character("친구","사용자가 쓴 원문",null,"현대",null,null,t());p.markAlive("sheet","identity",t());p.skipTutorial(t());
            return petRepository.saveAndFlush(p).getId();});return new Room(user,pet);
    }
    @BeforeEach void applySlotMigration(){
        // Isolated *_test database only: exercise the actual CREATE TABLE/ALTER paths, not Hibernate substitutes.
        jdbc.execute("drop table if exists zzal_diary_entry");
        for(String column:List.of("diary_absence_started_at","diary_absence_awake_sec","diary_opportunity_at","diary_opportunity_consumed","diary_language"))
            jdbc.execute("alter table zzal_pet drop column if exists "+column);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261016_2300__zzal_diary.sql")).execute(jdbc.getDataSource());
    }
    private void leave(Room r){transactions.execute(s->{var p=petRepository.findByIdForUpdate(r.pet).orElseThrow();p.startDiaryAbsence(t());return null;});}
    private Long ready(Room r,String body,String lang){
        Long id=transactions.execute(s->{var e=DiaryEntry.reserve(r.pet,UUID.randomUUID().toString(),t(),t(),lang,"{\"persona\":\"原文\"}");entries.saveAndFlush(e);return e.id();});
        var c=workers.claim(id,t()).orElseThrow();assertThat(workers.finish(c,new DiaryModel.Result(body,"stub:receipt"),t().plusSeconds(1))).isTrue();return id;
    }
    @Test void providerAdapterAndProductWorkerPreserveFictionReceiptWithoutNetwork(){
        Room r=room();leave(r);
        transactions.execute(s->{var p=petRepository.findById(r.pet).orElseThrow();p.diaryLanguage("ja");p.rememberExplicitCallMe("상훈");return null;});
        Long id=diary.reserveDue(r.pet,t().plusSeconds(14400)).orElseThrow();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var model=new ChatLineDiaryModel((system,input,name,timeout)->{
            calls.incrementAndGet();assertThat(input).contains("Japanese","DIARY_FICTION","사용자가 쓴 원문","상훈","EXPLICIT","legacySource").doesNotContain("USER_REPORTED");
            return new com.lore.zzal.chat.line.ChatLineClient.Completion("{\"body\":\"静かな午後だった。\"}",new java.math.BigDecimal("0.000123"),100,20);
        },"stub-model",Duration.ofSeconds(15));
        var worker=new DiaryScheduler(petRepository,entries,diary,workers,null);worker.run(id,model);worker.run(id,model);
        assertThat(calls.get()).isEqualTo(1);var result=entries.findById(id).orElseThrow();assertThat(result.body()).isEqualTo("静かな午後だった。");
        assertThat(result.language()).isEqualTo("ja");assertThat(result.status()).isEqualTo(DiaryEntry.Status.READY);
        assertThat(jdbc.queryForObject("select execution_receipt from zzal_diary_entry where id=?",String.class,id)).contains("stub-model","COMPLETED","0.000123","sourceHash");
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?",Long.class,r.pet)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from zzal_fact_memory where pet_id=?",Long.class,r.pet)).isZero();
    }
    @Test void actualReservationBoundaryAndConcurrentClaimAreSingle() throws Exception {
        Room r=room();leave(r);assertThat(diary.reserveDue(r.pet,t().plusSeconds(14399))).isEmpty();
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Callable<Optional<Long>>> calls=new ArrayList<>();for(int i=0;i<8;i++)calls.add(()->diary.reserveDue(r.pet,t().plusSeconds(14400)));
            var results=pool.invokeAll(calls);assertThat(results.stream().filter(f->{try{return f.get().isPresent();}catch(Exception e){throw new RuntimeException(e);}}).count()).isEqualTo(1);
            Long id=entries.findAll().getFirst().id();List<Callable<Boolean>> claims=new ArrayList<>();for(int i=0;i<8;i++)claims.add(()->workers.claim(id,t()).isPresent());
            assertThat(pool.invokeAll(claims).stream().filter(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).count()).isEqualTo(1);
        }finally{pool.shutdownNow();}
        assertThat(entries.count()).isEqualTo(1);assertThat(diary.reserveDue(r.pet,t().plusSeconds(8*3600))).isEmpty();
    }
    @Test void firstAckMatchesPostgresRoundTripAtLinuxNanosecondPrecision(){
        Room r=room();Long id=ready(r,"Immutable original.","en");
        Instant linuxNow=Instant.parse("2026-10-16T06:10:59.475058656Z");
        var first=diary.read(r.user,r.pet,id,linuxNow);
        var reloaded=DiaryResponses.Entry.from(entries.findById(id).orElseThrow());
        var replay=diary.read(r.user,r.pet,id,linuxNow.plusSeconds(1));
        assertThat(first).isEqualTo(reloaded).isEqualTo(replay);
        assertThat(first.readAt()).isEqualTo(Instant.parse("2026-10-16T06:10:59.475058Z"));
    }
    @Test void originalHistoryReadAckAndOwnershipThroughRealApi() throws Exception {
        Room r=room();Long id=ready(r,"窓辺で本を読んだ。","ja");String url="/api/zzal/v1/me/pets/"+r.pet+"/diary";
        var page=getJson(r.user,url).path("data");assertThat(page.path("unreadCount").asInt()).isEqualTo(1);
        assertThat(page.path("entries").get(0).path("body").asText()).isEqualTo("窓辺で本を読んだ。");
        assertThat(entries.findById(id).orElseThrow().readAt()).isNull();
        var first=postAs(r.user,url+"/"+id+"/read",Map.of());assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String at=body(first).path("data").path("readAt").asText();
        assertThat(body(postAs(r.user,url+"/"+id+"/read",Map.of())).path("data").path("readAt").asText()).isEqualTo(at);
        assertThat(getJson(r.user,url).path("data").path("entries").get(0).path("language").asText()).isEqualTo("ja");
        assertThat(postAs(newUserId(),url+"/"+id+"/read",Map.of()).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?",Long.class,r.pet)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from zzal_fact_memory where pet_id=?",Long.class,r.pet)).isZero();
    }
    @Test void unreadBlocksLongAbsenceAndReadDoesNotBankBacklog(){
        Room r=room();Long id=ready(r,"A quiet afternoon.","en");leave(r);
        assertThat(diary.reserveDue(r.pet,t().plusSeconds(14400))).isEmpty();diary.read(r.user,r.pet,id,t().plusSeconds(14401));
        assertThat(diary.reserveDue(r.pet,t().plusSeconds(20000))).isEmpty();assertThat(entries.count()).isEqualTo(1);
        transactions.execute(s->{var p=petRepository.findByIdForUpdate(r.pet).orElseThrow();p.endDiaryAbsence();p.startDiaryAbsence(t().plusSeconds(20000));return null;});
        assertThat(diary.reserveDue(r.pet,t().plusSeconds(34400))).isPresent();assertThat(entries.count()).isEqualTo(2);
    }
    @Test void selectedLanguageFreezesAndReturnPreservesExistingDiary(){
        Room r=room();transactions.execute(s->{petRepository.findById(r.pet).orElseThrow().beginFirstMeeting();return null;});
        presence.accept(r.user,r.pet,new ChatPresenceRequest(UUID.randomUUID(),UUID.randomUUID(),1,
                ChatPresenceRequest.Event.ENTER,ChatPresenceRequest.EntryKind.OPEN),t(),"ja");
        assertThat(petRepository.findById(r.pet).orElseThrow().getDiaryLanguage()).isEqualTo("ja");
        Long id=ready(r,"原文を保つ。","ja");String hash=entries.findById(id).orElseThrow().bodyHash();
        presence.accept(r.user,r.pet,new ChatPresenceRequest(UUID.randomUUID(),UUID.randomUUID(),1,
                ChatPresenceRequest.Event.ENTER,ChatPresenceRequest.EntryKind.OPEN),t().plusSeconds(2),"en");
        var stored=entries.findById(id).orElseThrow();assertThat(stored.bodyHash()).isEqualTo(hash);assertThat(stored.language()).isEqualTo("ja");
    }
    @Test void databaseSlotConstraintAndUnknownProcessPreventDuplicatePaidWork(){
        Room r=room();Long id=ready(r,"Small day.","en");
        assertThatThrownBy(()->transactions.execute(s->{entries.saveAndFlush(DiaryEntry.reserve(r.pet,"another",t(),t(),"ko","{}"));return null;})).isInstanceOf(RuntimeException.class);
        diary.read(r.user,r.pet,id,t());Long second=transactions.execute(s->{var e=entries.saveAndFlush(DiaryEntry.reserve(r.pet,"second",t(),t(),"ko","{}"));return e.id();});
        workers.claim(second,t());workers.recoverUncertain(t().plusSeconds(1));assertThat(workers.claim(second,t().plusSeconds(2))).isEmpty();
        assertThat(entries.findById(second).orElseThrow().status()).isEqualTo(DiaryEntry.Status.UNCERTAIN);
    }
    @Test void recoveryCannotOverwriteAConcurrentCompletedOriginal() throws Exception {
        Room r=room();Long id=transactions.execute(s->{var e=entries.saveAndFlush(DiaryEntry.reserve(r.pet,"recovery-race",t(),t(),"ko","{}"));return e.id();});
        var claim=workers.claim(id,t()).orElseThrow();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var finishing=pool.submit(()->transactions.execute(s->{var e=entries.locked(id).orElseThrow();locked.countDown();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){throw new RuntimeException(ex);}
                return e.complete(claim.token(),"보존해야 할 원문","stub",t());}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var recovering=pool.submit(()->workers.recoverUncertain(t().plusSeconds(1)));
            assertThatThrownBy(()->recovering.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();assertThat(finishing.get(5,TimeUnit.SECONDS)).isTrue();recovering.get(5,TimeUnit.SECONDS);
            var finalEntry=entries.findById(id).orElseThrow();assertThat(finalEntry.status()).isEqualTo(DiaryEntry.Status.READY);
            assertThat(finalEntry.body()).isEqualTo("보존해야 할 원문");
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void cursorUsesWrittenAtAndIdWhenCompletionOrderDiffers(){
        Room r=room();Long first=ready(r,"First.","en");diary.read(r.user,r.pet,first,t());
        Long second=transactions.execute(s->{var e=entries.saveAndFlush(DiaryEntry.reserve(r.pet,"older-time-newer-id",t().minusSeconds(100),t(),"en","{}"));return e.id();});
        var c=workers.claim(second,t()).orElseThrow();workers.finish(c,new DiaryModel.Result("Second.","stub"),t());
        var one=diary.list(r.user,r.pet,null,1);assertThat(one.entries().getFirst().id()).isEqualTo(first);
        var two=diary.list(r.user,r.pet,one.nextBeforeId(),1);assertThat(two.entries().getFirst().id()).isEqualTo(second);assertThat(two.nextBeforeId()).isNull();
    }
    @Test void confirmedPresenceMultiTabAndLostLeaveAreConservative(){
        Room r=room(); // Force unavailable chat so presence exercises no model at all.
        transactions.execute(s->{petRepository.findById(r.pet).orElseThrow().beginFirstMeeting();return null;});
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        send(r,a,1,true,t());send(r,b,1,true,t());send(r,a,2,false,t().plusSeconds(1));
        assertThat(petRepository.findById(r.pet).orElseThrow().getDiaryAbsenceStartedAt()).isNull();
        send(r,b,2,false,t().plusSeconds(2));send(r,b,3,false,t().plusSeconds(3));
        assertThat(petRepository.findById(r.pet).orElseThrow().getDiaryAbsenceStartedAt()).isEqualTo(t().plusSeconds(2));
        send(r,a,3,true,t().plusSeconds(5));assertThat(petRepository.findById(r.pet).orElseThrow().getDiaryAbsenceStartedAt()).isNull();
        // No leave for a: an orphaned active tab must not invent paid absence credit.
        assertThat(diary.reserveDue(r.pet,t().plusSeconds(20000))).isEmpty();
    }
    private void send(Room r,UUID id,long seq,boolean enter,Instant now){presence.accept(r.user,r.pet,new ChatPresenceRequest(id,UUID.randomUUID(),seq,enter?ChatPresenceRequest.Event.ENTER:ChatPresenceRequest.Event.LEAVE,enter?ChatPresenceRequest.EntryKind.OPEN:null),now);}
}
