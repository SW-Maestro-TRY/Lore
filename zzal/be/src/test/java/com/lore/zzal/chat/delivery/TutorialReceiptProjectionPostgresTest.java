package com.lore.zzal.chat.delivery;
import com.lore.zzal.chat.ChatSlot;import com.lore.zzal.chat.dto.ChatRequests;
import com.lore.zzal.chat.prompt.ConversationScene;
import org.hibernate.SessionFactory;import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.domain.PageRequest;import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;import java.util.*;import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="LORE_TUTORIAL_RECEIPT_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55420/lore_tutorial_receipt_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TutorialReceiptProjectionPostgresTest {
 SessionFactory sf;
 @BeforeAll void schema(){sf=new Configuration().addAnnotatedClass(ZzalChatDelivery.class)
  .setProperty("hibernate.connection.url",System.getenv("LORE_TUTORIAL_RECEIPT_DB_URL"))
  .setProperty("hibernate.connection.username",System.getenv("LORE_TEST_DB_USERNAME"))
  .setProperty("hibernate.connection.password",System.getenv("LORE_TEST_DB_PASSWORD"))
  .setProperty("hibernate.hbm2ddl.auto","create").setProperty("hibernate.connection.pool_size","2").setProperty("hibernate.show_sql","false").buildSessionFactory();}
 @AfterAll void close(){if(sf!=null)sf.close();}
 ZzalChatDelivery row(long pet,String text,String json,Instant at){var request=new ChatRequests.Answer(text,8L,0,UUID.randomUUID());return new ZzalChatDelivery(pet,ChatSlot.CONTINUOUS,request,json,at);}
 @Test void actualRepositoryUsesJsonStructureNotWhitespaceOrderOrRecentGeneralWindow(){
  var now=Instant.parse("2026-10-18T03:00:00Z");
  try(var session=sf.openSession()){var tx=session.beginTransaction();
   session.persist(row(1,"옛답","{\"_tutorial\":{\"step\":\"FEED\",\"answerType\":\"FREE_TEXT\"}}",now));
   session.persist(row(1,"최신답","{ \"other\": true, \"_tutorial\" : { \"answerType\" : \"SUGGESTED\", \"step\" : \"FEED\" } }",now.plusSeconds(1)));
   session.persist(row(1,"게임답","{\"_tutorial\":{\"answerType\":\"FREE_TEXT\",\"step\":\"GAME\"}}",now.plusSeconds(2)));
   for(int i=0;i<45;i++)session.persist(row(1,"일반대화","{\"chatReply\":{\"line\":\"일반말\"}}",now.plusSeconds(3+i)));
   session.persist(row(2,"다른주인","{\"_tutorial\":{\"step\":\"FEED\",\"answerType\":\"FREE_TEXT\"}}",now.plusSeconds(100)));tx.commit();
  }
  try(var session=sf.openSession()){var repository=new JpaRepositoryFactory(session).getRepository(ZzalChatDeliveryRepository.class);
   var feed=repository.latestTutorial(1L,"CONTINUOUS","FEED",PageRequest.of(0,1));assertThat(feed).hasSize(1);assertThat(feed.getFirst().text()).isEqualTo("최신답");assertThat(feed.getFirst().tutorial().orElseThrow().answerType()).isEqualTo(ChatRequests.Answer.AnswerType.SUGGESTED);
   var game=repository.latestTutorial(1L,"CONTINUOUS","GAME",PageRequest.of(0,1));assertThat(game).hasSize(1);assertThat(game.getFirst().text()).isEqualTo("게임답");
   assertThat(repository.latestTutorial(99L,"CONTINUOUS","FEED",PageRequest.of(0,1))).isEmpty();
  }
 }
}
