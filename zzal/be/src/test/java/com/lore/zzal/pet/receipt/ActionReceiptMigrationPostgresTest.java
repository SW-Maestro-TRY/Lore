package com.lore.zzal.pet.receipt;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;import java.util.*;import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="LORE_ACTION_MIGRATION_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55420/lore_action_receipt_migration_[0-9_]+_test")
class ActionReceiptMigrationPostgresTest {
 LocalContainerEntityManagerFactoryBean factory(DriverManagerDataSource ds,String mode){var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.lore.zzal.pet");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto",mode,"hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl","hibernate.show_sql","false"));f.afterPropertiesSet();return f;}
 @Test void flywayAddsReceiptAfterHistoricalRowsAndJpaBootValidationPasses()throws Exception{
  var ds=new DriverManagerDataSource(System.getenv("LORE_ACTION_MIGRATION_DB_URL"),System.getenv("LORE_TEST_DB_USERNAME"),System.getenv("LORE_TEST_DB_PASSWORD"));var jdbc=new JdbcTemplate(ds);
  var first=org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("20261018.0010").load().migrate();assertThat(first.migrationsExecuted).isPositive();assertThat(jdbc.queryForObject("select to_regclass('public.zzal_action_receipt') is null",Boolean.class)).isTrue();
  var before=factory(ds,"none");var beforeEmf=before.getObject();Long historical;
  try{var em=SharedEntityManagerCreator.createSharedEntityManager(beforeEmf);var tx=new TransactionTemplate(new JpaTransactionManager(beforeEmf));historical=tx.execute(s->{var now=Instant.parse("2026-10-18T03:00:00Z");var p=ZzalPet.draft(900003L,"images/synthetic/migration-before",now);ReflectionTestUtils.setField(p,"createdAt",now);ReflectionTestUtils.setField(p,"updatedAt",now);p.rememberCareMotion("PET",new ZzalPet.CareMotionCursor("base",null));em.persist(p);em.flush();return p.getId();});}finally{before.destroy();}
  var result=org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();assertThat(result.migrationsExecuted).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='20261018.2200' and success",Integer.class)).isEqualTo(1);
  var validated=factory(ds,"validate");try{var emf=validated.getObject();var em=SharedEntityManagerCreator.createSharedEntityManager(emf);var tx=new TransactionTemplate(new JpaTransactionManager(emf));tx.execute(s->{var p=em.find(ZzalPet.class,historical);assertThat(p.getUserId()).isEqualTo(900003L);assertThat(p.getSourceImageKey()).isEqualTo("images/synthetic/migration-before");assertThat(p.careMotionCursor("PET").id()).isEqualTo("base");assertThat(p.careMotionCursor("PET").setId()).isNull();var id=UUID.randomUUID();var receipt=new ActionReceipt(historical,id,"a".repeat(64),"{\"justUnlocked\":[],\"justHealed\":false,\"motionPlayback\":null}",Instant.parse("2026-10-18T03:00:00Z"));em.persist(receipt);em.flush();return null;});assertThat(jdbc.queryForObject("select count(*) from zzal_action_receipt",Integer.class)).isEqualTo(1);}finally{validated.destroy();}
  assertThat(org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate().migrationsExecuted).isZero();
  java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("LORE_ACTION_MIGRATION_EVIDENCE")),"{\"oldMigrations\":"+first.migrationsExecuted+",\"newMigrationCount\":1,\"jpaValidate\":true,\"historicalOwnerKeyCursorPreserved\":true,\"newReceiptPersisted\":true,\"repeatMigrations\":0}");
 }
}
