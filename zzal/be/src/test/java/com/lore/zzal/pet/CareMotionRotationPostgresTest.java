package com.lore.zzal.pet;
import com.lore.zzal.attendance.*;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated real PostgreSQL/real Hibernate JSON mapping. Never connects to a runtime DB. */
@EnabledIfEnvironmentVariable(named="LORE_CARE_ROTATION_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55420/lore_care_rotation_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CareMotionRotationPostgresTest {
 SessionFactory sf;CareMotionRotationService service;Long petId;Long historicalId;
 @BeforeAll void schema(){
  sf=new Configuration().addAnnotatedClass(ZzalPet.class)
   .setProperty("hibernate.connection.url",System.getenv("LORE_CARE_ROTATION_DB_URL"))
   .setProperty("hibernate.connection.username",System.getenv("LORE_TEST_DB_USERNAME"))
   .setProperty("hibernate.connection.password",System.getenv("LORE_TEST_DB_PASSWORD"))
   .setProperty("hibernate.hbm2ddl.auto","create")
   .setProperty("hibernate.connection.pool_size","2")
   .setProperty("hibernate.show_sql","false").buildSessionFactory();
  try(var s=sf.openSession()){var tx=s.beginTransaction();var now=Instant.parse("2026-10-18T03:00:00Z");var p=ZzalPet.draft(99L,"images/historical",now);ReflectionTestUtils.setField(p,"createdAt",now);ReflectionTestUtils.setField(p,"updatedAt",now);s.persist(p);tx.commit();historicalId=p.getId();}
  // Execute the actual migration on the old-schema shape, preserving pre-existing rows.
  try(var s=sf.openSession()){s.doWork(c->{try(var st=c.createStatement()){st.execute("ALTER TABLE zzal_pet DROP COLUMN care_motion_cursor");}org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("db/migration/V20261018_0010__zzal_care_motion_cursor.sql"));});}
  var own=mock(CustomMotionOwnershipService.class);
  when(own.owned(anyLong(),anyLong())).thenReturn(List.of(CareMotionRotationServiceTest.motion(10,1,false),CareMotionRotationServiceTest.motion(20,1,false)));
  service=new CareMotionRotationService(own);
 }
 @BeforeEach void seed(){try(var s=sf.openSession()){var tx=s.beginTransaction();var now=Instant.parse("2026-10-18T03:00:00Z");var p=ZzalPet.draft(1L,"images/source",now);ReflectionTestUtils.setField(p,"createdAt",now);ReflectionTestUtils.setField(p,"updatedAt",now);s.persist(p);tx.commit();petId=p.getId();}}
 @AfterAll void close(){if(sf!=null)sf.close();}
 @Test void actualMigrationPreservesExistingPetAndLeavesCursorNull(){try(var s=sf.openSession()){var p=s.find(ZzalPet.class,historicalId);assertThat(p.getUserId()).isEqualTo(99L);assertThat(p.careMotionCursor("PET")).isNull();}}
 @Test void historicalNullAndJsonCursorSurviveFreshSessions(){
  try(var s=sf.openSession()){assertThat(s.find(ZzalPet.class,petId).careMotionCursor("PET")).isNull();}
  List<String> seen=new ArrayList<>();for(int i=0;i<4;i++)try(var s=sf.openSession()){var tx=s.beginTransaction();var p=s.find(ZzalPet.class,petId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);seen.add(service.afterSuccess(p,CareAction.PET).motionId());tx.commit();}
  assertThat(seen).containsExactly("base","custom:10","custom:20","base");
  try(var s=sf.openSession()){assertThat(s.find(ZzalPet.class,petId).careMotionCursor("PET").id()).isEqualTo("base");}
 }
 @Test void rollbackDoesNotConsumeLastSuccessfulSelection(){
  try(var s=sf.openSession()){var tx=s.beginTransaction();service.afterSuccess(s.find(ZzalPet.class,petId),CareAction.PET);tx.commit();}
  try(var s=sf.openSession()){var tx=s.beginTransaction();assertThat(service.afterSuccess(s.find(ZzalPet.class,petId),CareAction.PET).motionId()).isEqualTo("custom:10");s.flush();tx.rollback();}
  try(var s=sf.openSession()){var tx=s.beginTransaction();assertThat(service.afterSuccess(s.find(ZzalPet.class,petId),CareAction.PET).motionId()).isEqualTo("custom:10");tx.commit();}
 }
 @Test void concurrentSuccessfulOccurrencesSerializeAtExistingPetRowLock()throws Exception{
  var ready=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{var a=pool.submit(()->{try(var s=sf.openSession()){var tx=s.beginTransaction();var p=s.find(ZzalPet.class,petId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);String id=service.afterSuccess(p,CareAction.PET).motionId();ready.countDown();release.await();tx.commit();return id;}});assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var b=pool.submit(()->{try(var s=sf.openSession()){var tx=s.beginTransaction();var p=s.find(ZzalPet.class,petId,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);String id=service.afterSuccess(p,CareAction.PET).motionId();tx.commit();return id;}});release.countDown();assertThat(a.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("base");assertThat(b.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("custom:10");}finally{release.countDown();pool.shutdownNow();}
 }
}
