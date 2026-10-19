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
  assertThat(new HashSet<>(systems)).hasSize(2);
  assertThat(systems.get(0)).isEqualTo(systems.get(1)).doesNotContain("[현재 장면: 일반 채팅]");
  assertThat(systems.get(2)).contains("[현재 장면: 일반 채팅]", "별도 인물의 예시");
  assertThat(inputs).allSatisfy(s->assertThat(s).contains("차가 좋아","USER","custom:z","revision").doesNotContain("albumOnly-secret"));
  assertThat(inputs.get(1)).contains("TUTORIAL","FEED","행동 성공");
 }
 @Test void malformedServerSceneRejected(){assertThatThrownBy(()->new ConversationScene(ConversationScene.Phase.TUTORIAL,null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new ConversationScene(ConversationScene.Phase.CONTINUOUS,ConversationScene.Step.BATH)).isInstanceOf(IllegalArgumentException.class);}
 @Test void ordinaryStateIsBackgroundButGuidedScenesKeepTheirPurpose(){
  var sheet=new PersonaSheet("별이",List.of(),null,null,null,"작가님",false);
  var ctx=new ChatContext(23L,sheet,new PetState(DayOfWeek.MONDAY,12,0,1,0,1,0,false),SessionKind.BABY,new TurnPlan(TurnType.CONTINUE,2,false,null,true,ChatSlot.CONTINUOUS),null,List.of(new HistoryLine(Speaker.PET,"아까 배가 고팠어"),new HistoryLine(Speaker.USER,"오늘 시험을 봤어")),List.of());
  var ordinary=PromptAssembler.user(ctx);
  assertThat(ctx.scene()).isNull();
  assertThat(ordinary).contains("캐릭터 자신의 상태(사용자의 상태 아님)","포만=배고픔","기분=시무룩","청결=꼬질꼬질","대화의 배경 정보","직접 물었거나","같은 하소연","너: 아까 배가 고팠어","상대: 오늘 시험을 봤어");
  assertThat(PromptAssembler.system(sheet)).contains("별이","작가님","JSON 한 줄");
  for(var scene:List.of(new ConversationScene(ConversationScene.Phase.TUTORIAL,ConversationScene.Step.FEED),new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE,ConversationScene.Step.FEED))){
   assertThat(PromptAssembler.user(ctx.withScene(scene))).contains("캐릭터 자신의 상태","상태와 마음").doesNotContain("대화의 배경 정보");
  }
  assertThat(PromptAssembler.user(ctx.withScene(new ConversationScene(ConversationScene.Phase.CONTINUOUS,null)))).contains("대화의 배경 정보");
  assertThat(PromptAssembler.user(ctx.withScene(new ConversationScene(ConversationScene.Phase.FIRST_MEETING,null))))
   .doesNotContain("캐릭터 자신의 상태", "포만=", "기분=", "청결=", "건강=", "상태와 마음", "대화의 배경 정보");
 }

 @Test void firstMeetingProviderInputsIgnoreAllGaugesAndSeparateDiscoveryFromAddressGreeting(){
  var fields=List.of(new AuthoredPersona.Field("job",List.of("학생"),"낯선 곳을 조심스럽게 살핀다",false));
  var scenes=List.of(new ConversationScene(ConversationScene.Phase.FIRST_MEETING,ConversationScene.Step.FIRST_MEETING_DISCOVERY),new ConversationScene(ConversationScene.Phase.FIRST_MEETING,null));
  for(var scene:scenes){
   boolean discovery=scene.step()==ConversationScene.Step.FIRST_MEETING_DISCOVERY;
   var sheet=new PersonaSheet("별이",List.of(),null,null,null,discovery?null:"별님",false,new AuthoredPersona(1,fields),"ja");
   var sent=new ArrayList<String>();
   ChatLineClient client=(system,input,model,timeout)->{sent.add(system+"\n"+input);return new ChatLineClient.Completion("{\"line\":\"반가워요\",\"motion\":\"base\",\"memory_deltas\":[]}",BigDecimal.ZERO,0,0);};
   var generator=new LlmLineGenerator(client,"stub-only",Duration.ofSeconds(1),new SystemPromptCache());
   for(var state:List.of(new PetState(DayOfWeek.MONDAY,12,0,1,0,0,0,true),new PetState(DayOfWeek.MONDAY,12,0,1,4,4,4,false))){
    var ctx=new ChatContext(23L,sheet,state,SessionKind.BABY,new TurnPlan(TurnType.FIRST_MEET,1,false,null,false,ChatSlot.CONTINUOUS),null,List.of(),List.of()).withScene(scene);
    assertThat(generator.generate(ctx).ok()).isTrue();
   }
   assertThat(sent).hasSize(2);assertThat(sent.get(0)).isEqualTo(sent.get(1));
   assertThat(sent.getFirst()).contains("학생","낯선 곳을 조심스럽게 살핀다","일본어","낯선 주변의","상대에 대한 관심")
    .doesNotContain("포만=","기분=","청결=","건강=","꼬질꼬질","시무룩","아프지 않음","상태와 마음");
   if(discovery){
    assertThat(sent.getFirst()).contains("아직 호칭은 확정되지 않았다","호칭 선택을 아직 받지 않은 발견 장면")
     .doesNotContain("저장된 호칭 선택은 이미 확인했다","저장된 호칭 선택을 확인한 뒤의 첫 인사");
   }else{
    assertThat(sent.getFirst()).contains("상대를 부르는 말: 별님","저장된 호칭 선택을 확인한 뒤의 첫 인사","이름이나 호칭을 다시 묻지 않고")
     .doesNotContain("아직 호칭은 확정되지 않았다","호칭 선택을 아직 받지 않은 발견 장면");
   }
  }
 }
}
