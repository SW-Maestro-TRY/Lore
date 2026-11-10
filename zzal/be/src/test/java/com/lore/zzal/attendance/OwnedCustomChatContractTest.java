package com.lore.zzal.attendance;
import com.lore.zzal.chat.line.*;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.prompt.*;
import com.lore.zzal.chat.session.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class OwnedCustomChatContractTest {
 static OwnedCustomMotion motion(long id,boolean album){return new OwnedCustomMotion(CustomMotionOwnershipService.actionId(id),1L,1,"도시락을 든 인사","도시락을 보여주며 인사","hello",album,"images/custom/hello.webp",null,"eat_rice",null,true,true,"CUSTOM");}
 @Test void idsAreOpaqueDistinctAndFitExistingTurnColumn(){
  assertThat(CustomMotionOwnershipService.actionId(35)).isEqualTo("custom:z");
  assertThat(CustomMotionOwnershipService.actionId(36)).isEqualTo("custom:10");
  Set<String> ids=new HashSet<>();for(long id:List.of(1L,35L,36L,Long.MAX_VALUE-1,Long.MAX_VALUE)){String value=CustomMotionOwnershipService.actionId(id);assertThat(value.length()).isLessThanOrEqualTo(20);assertThat(ids.add(value)).isTrue();}
  assertThatThrownBy(()->CustomMotionOwnershipService.actionId(0)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void freeAndUnownedActionsCannotBeSelected(){
  var options=CustomChatActions.allowed(List.of(motion(35,false),motion(36,true)));
  assertThat(options).extracting(ChatContext.ActionOption::id).containsExactly("base","custom:z");
  assertThat(CustomChatActions.select("custom:10",options)).isEqualTo("base");
  assertThat(CustomChatActions.select("custom:unowned",options)).isEqualTo("base");
  assertThat(CustomChatActions.select(null,options)).isEqualTo("base");
 }
 @Test void sameLineResponseSelectsOwnedIdWithoutSecondModelCall(){
  var options=CustomChatActions.allowed(List.of(motion(Long.MAX_VALUE,false),motion(36,true)));
  var context=new ChatContext(7L,new PersonaSheet("루아나",List.of(),null,null,null,null,false),new PetState(DayOfWeek.THURSDAY,21,0,3,3,3,3,false),SessionKind.DAILY,new TurnPlan(TurnType.CONTINUE,2,false,null,false),null,List.of(),List.of("base")).withActionOptions(options);
  String id=options.get(1).id();var client=new FakeChatLineClient().line("같이 먹자",id);
  var result=new LlmLineGenerator(client,"stub",Duration.ofSeconds(4),new SystemPromptCache()).generate(context);
  assertThat(result.ok()).isTrue();assertThat(result.motion()).isEqualTo(id);
  assertThat(client.users).hasSize(1);assertThat(client.users.getFirst()).contains(id,"도시락을 든 인사").doesNotContain("custom:10");
  var invalid=new FakeChatLineClient().line("응","custom:10");
  assertThat(new LlmLineGenerator(invalid,"stub",Duration.ofSeconds(4),new SystemPromptCache()).generate(context).motion()).isEqualTo("base");
  assertThat(invalid.users).hasSize(1);
 }
 @Test void existingPetAndAlbumResponsesSerializeTheSameOwnedAssets()throws Exception{
  var owned=List.of(motion(35,false),motion(36,true));
  var pet=com.lore.zzal.pet.ZzalPet.draft(11L,"images/source",Instant.parse("2026-10-17T00:00:00Z"));
  pet.setAttendanceRewards(new AttendanceRewards("first-v1",3,"GENERATED",0,List.of(),null,owned));
  var detail=com.lore.zzal.pet.dto.PetResponses.Detail.fromWithoutPieces(pet,null,Instant.parse("2026-10-17T00:01:00Z"),new com.lore.zzal.motion.MotionCatalog("","","v1"));
  var album=new com.lore.zzal.pet.dto.PetResponses.Album(List.of(),List.of(),List.of(),null,detail.customMotions());
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
  var detailJson=mapper.valueToTree(detail);var albumJson=mapper.valueToTree(album);
  assertThat(detailJson.get("customMotions")).isNotNull().isEqualTo(albumJson.get("customMotions"));
  assertThat(detailJson.get("customMotions").get(0).get("id").asText()).isEqualTo("custom:z");
  assertThat(detailJson.get("customMotions").get(1).get("albumOnly").asBoolean()).isTrue();
 }

}
