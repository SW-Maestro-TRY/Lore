package com.lore.zzal.chat.prompt;
import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.line.*;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.session.*;
import com.lore.zzal.pet.persona.AuthoredPersona;
import com.lore.zzal.chat.memory.facts.FactView;
import org.junit.jupiter.api.Test;
import java.util.*;import java.time.*;import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class ConversationScenePromptTest {
 @Test void allScenesReachRealAssemblerThroughSameChainWithoutProvider(){
  var fields=List.of("world","job","persona","likes","dislikes","extra").stream().map(id->new AuthoredPersona.Field(id,List.of("chip-"+id),"원문-"+id,false)).toList();
  var sheet=new PersonaSheet("다른아이",List.of(),null,null,null,"작가님",false,new AuthoredPersona(1,fields),"ja");
  var fact=new FactView(3L,"USER",null,"사용자는 차를 좋아함","USER_REPORTED","ACTIVE",2L,"차가 좋아",Instant.EPOCH,null,2,"ADD");
  var ctx=new ChatContext(23L,sheet,new PetState(DayOfWeek.MONDAY,12,0,1,1,2,3,false),SessionKind.BABY,new TurnPlan(TurnType.CONTINUE,2,false,null,true,ChatSlot.CONTINUOUS),null,List.of(new HistoryLine(Speaker.USER,"차가 좋아")),List.of()).withFacts(List.of(fact)).withActionOptions(List.of(new ChatContext.ActionOption("custom:z","반응","조용한 반응","pet")));
  var systems=new ArrayList<String>();var inputs=new ArrayList<String>();
  ChatLineClient spy=(system,input,model,timeout)->{systems.add(system);inputs.add(input);return new ChatLineClient.Completion("{\"line\":\"알겠어\",\"motion\":\"custom:z\",\"memory_deltas\":[]}",BigDecimal.ZERO,0,0);};
  var chain=new LineChain(new LlmLineGenerator(spy,"spy-only",Duration.ofSeconds(1),new SystemPromptCache()),null);
  var scenes=List.of(new ConversationScene(ConversationScene.Phase.FIRST_MEETING,null),new ConversationScene(ConversationScene.Phase.TUTORIAL,ConversationScene.Step.FEED),new ConversationScene(ConversationScene.Phase.CONTINUOUS,null));
  for(var scene:scenes){var c=ctx.withScene(scene).withHistory(ctx.history()).withFacts(ctx.factMemories()).withActionOptions(ctx.actionOptions());assertThat(c.scene()).isEqualTo(scene);var line=chain.generate(c,8L);assertThat(line.motion()).isEqualTo("custom:z");}
  assertThat(systems).hasSize(3).allSatisfy(s->{assertThat(s).contains("다른아이","작가님","일본어").doesNotContain("로미");for(var f:fields)assertThat(s).contains(f.rawText());});
  assertThat(new HashSet<>(systems)).hasSize(1);
  assertThat(inputs).allSatisfy(s->assertThat(s).contains("차가 좋아","USER","custom:z","revision").doesNotContain("albumOnly-secret"));
  assertThat(inputs.get(1)).contains("TUTORIAL","FEED","행동 성공");
 }
 @Test void malformedServerSceneRejected(){assertThatThrownBy(()->new ConversationScene(ConversationScene.Phase.TUTORIAL,null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new ConversationScene(ConversationScene.Phase.CONTINUOUS,ConversationScene.Step.BATH)).isInstanceOf(IllegalArgumentException.class);}
}
