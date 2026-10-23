package com.lore.webtoon.series;
import com.lore.common.credit.*;
import com.lore.common.exception.BusinessException;
import com.lore.webtoon.credit.CreditGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=SeriesBillingPersistenceTest.Config.class)
@EnabledIfEnvironmentVariable(named="SERIES_TEST_DB_URL",matches=".+")
class SeriesBillingPersistenceTest {
 @SpringBootConfiguration @EnableAutoConfiguration @EntityScan("com.lore") @EnableJpaRepositories("com.lore")
 @Import({CreditService.class,CreditLedger.class,SeriesBilling.class})
 static class Config {@Bean CreditGate gate(){return mock(CreditGate.class);}}
 @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",()->System.getenv("SERIES_TEST_DB_URL"));p.add("spring.datasource.username",()->System.getenv().getOrDefault("SERIES_TEST_DB_USER","mint"));p.add("spring.datasource.password",()->"");}
 @Autowired SeriesBilling billing;@Autowired CreditService credits;@Autowired CreditEventRepository events;
 @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
 void differentSeriesCannotBothSpendOneEpisodesBalance() throws Exception {
  long user=998711L;
  events.deleteAll(events.allOf(user));credits.grantOnce(user,12,CreditReason.WELCOME,"series-concurrency-test");
  var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var a=pool.submit(()->charge(user,"series-a",ready,start));var b=pool.submit(()->charge(user,"series-b",ready,start));
   assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
   assertThat(a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS)).isEqualTo(1);
   assertThat(credits.balance(user)).isZero();
   assertThat(events.allOf(user).stream().filter(e->e.getReason()==CreditReason.SPEND).count()).isEqualTo(1);
  } finally {events.deleteAll(events.allOf(user));}
 }
 private int charge(long user,String job,CountDownLatch ready,CountDownLatch start) throws Exception {
  ready.countDown();start.await();
  try {billing.charge(user,12,job,"next episode");return 1;}catch(BusinessException error){assertThat(error.getErrorCode()).isEqualTo(com.lore.common.exception.ErrorCode.CREDIT_NOT_ENOUGH);return 0;}
 }
}
