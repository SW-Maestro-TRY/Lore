package com.lore.zzal.chat.delivery;
import com.lore.zzal.chat.dto.ChatRequests;import com.lore.zzal.chat.ChatSlot;import com.lore.zzal.chat.prompt.ConversationScene;
import org.junit.jupiter.api.Test;import java.time.Instant;import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class TutorialDeliveryContractTest {
 ChatRequests.Answer request(String text,ConversationScene.Step step,ChatRequests.Answer.AnswerType type,UUID id){return new ChatRequests.Answer(text,1L,0,id,new ChatRequests.Answer.Tutorial(step,type));}
 @Test void storedResponseRetainsIdentityButDoesNotExposeInternalMetadata(){
  var id=UUID.randomUUID();var req=request("원문",ConversationScene.Step.FEED,ChatRequests.Answer.AnswerType.FREE_TEXT,id);
  var saved=new ZzalChatDelivery(3L,ChatSlot.CONTINUOUS,req,"{\"chatReply\":{\"line\":\"답\"}}",Instant.EPOCH);
  assertThat(saved.matches(ChatSlot.CONTINUOUS,req)).isTrue();
  assertThat(saved.matches(ChatSlot.CONTINUOUS,request("원문",ConversationScene.Step.GAME,ChatRequests.Answer.AnswerType.FREE_TEXT,id))).isFalse();
  assertThat(saved.matches(ChatSlot.CONTINUOUS,request("원문",ConversationScene.Step.FEED,ChatRequests.Answer.AnswerType.SUGGESTED,id))).isFalse();
  assertThat(saved.matches(ChatSlot.CONTINUOUS,new ChatRequests.Answer("원문",1L,0,id))).isFalse();
  assertThat(saved.responseJson()).doesNotContain("_tutorial").contains("답");
 }
 @Test void oldReceiptsRemainCompatibleAndInvalidTutorialCannotBypassProtectedDelivery(){
  var id=UUID.randomUUID();var old=new ChatRequests.Answer("원문",1L,0,id);
  var saved=new ZzalChatDelivery(3L,ChatSlot.CONTINUOUS,old,"{}",Instant.EPOCH);assertThat(saved.matches(ChatSlot.CONTINUOUS,old)).isTrue();
  assertThat(new ChatRequests.Answer("원문",null,null,null,new ChatRequests.Answer.Tutorial(ConversationScene.Step.FEED,ChatRequests.Answer.AnswerType.FREE_TEXT)).isDeliveryComplete()).isFalse();
  assertThat(request("원문",ConversationScene.Step.BATH,ChatRequests.Answer.AnswerType.FREE_TEXT,id).isDeliveryComplete()).isFalse();
 }
}
