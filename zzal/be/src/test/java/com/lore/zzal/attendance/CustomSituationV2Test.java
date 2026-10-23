package com.lore.zzal.attendance;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CustomSituationV2Test {
 final ObjectMapper json=new ObjectMapper();
 CustomMotionPlan.Motion motion(int i,List<String> tags,boolean album){return new CustomMotionPlan.Motion(i,"title","action","reason",CustomMotionPlan.Basis.PERSONA_CREATIVE,List.of(),album?null:"joy",album,"standing: clear",List.of(),"stage",tags);}
 List<CustomMotionPlan.Motion> motions(List<String> tags){return java.util.stream.IntStream.rangeClosed(1,8).mapToObj(i->motion(i,i==1?tags:List.of(),i!=1)).toList();}
 @Test void v1MissingTagsReadsAndNeverGainsInferredSituations()throws Exception{
  var plan=new CustomMotionPlan(CustomMotionPlan.VERSION,motions(List.of()));var node=json.valueToTree(plan);for(var m:node.path("motions"))((com.fasterxml.jackson.databind.node.ObjectNode)m).remove("situations");
  var old=json.treeToValue(node,CustomMotionPlan.class);assertThat(old.version()).isEqualTo(CustomMotionPlan.VERSION);assertThat(old.motions()).allSatisfy(m->assertThat(m.situations()).isEmpty());
 }
 @Test void v2RequiresExplicitArrayAndRejectsV1TaggedRewrite()throws Exception{
  var plan=new CustomMotionPlan(CustomMotionPlan.CURRENT_VERSION,motions(List.of("FEED")));var node=json.valueToTree(plan);((com.fasterxml.jackson.databind.node.ObjectNode)node.path("motions").get(0)).remove("situations");assertThatThrownBy(()->json.treeToValue(node,CustomMotionPlan.class)).isInstanceOf(Exception.class);
  assertThatThrownBy(()->new CustomMotionPlan(CustomMotionPlan.VERSION,motions(List.of("FEED")))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void freeAndChatOnlyRemainUntaggedAndNoEightCareQuota()throws Exception{
  var plan=new CustomMotionPlan(CustomMotionPlan.CURRENT_VERSION,motions(List.of()));assertThat(plan.motions()).allSatisfy(m->assertThat(m.situations()).isEmpty());assertThat(json.readValue(json.writeValueAsString(plan),CustomMotionPlan.class)).isEqualTo(plan);
 }
 @Test void unknownDuplicateAndFreeTaggedAreRejected(){
  assertThatThrownBy(()->motion(1,List.of("GAME"),false)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->motion(1,List.of("FEED","FEED"),false)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->motion(1,List.of("BATH"),true)).isInstanceOf(IllegalArgumentException.class);
 }
 OwnedCustomMotion own(String version,List<String> tags,String semantic){return new OwnedCustomMotion("custom:1",1L,1,"title","description",semantic,false,"images/c.webp","images/a.json","physical_slot",3,true,true,"CUSTOM",version,tags);}
 @Test void v1OnlyPetAndV2NeverGuessesFromSemantic(){
  assertThat(own(CustomMotionPlan.VERSION,List.of(),"pet").careEligible("PET")).isTrue();assertThat(own(CustomMotionPlan.VERSION,List.of(),"eat").careEligible("FEED")).isFalse();assertThat(own(CustomMotionPlan.CURRENT_VERSION,List.of("SNACK"),"eat").careEligible("FEED")).isFalse();assertThat(own(CustomMotionPlan.CURRENT_VERSION,List.of("SNACK"),"joy").careEligible("SNACK")).isTrue();
 }
 @Test void legacyOwnedConstructorKeepsV1EmptyTags(){var m=new OwnedCustomMotion("custom:1",1L,1,"a","b","pet",false,"images/a",null,"slot",null,false,true,"CUSTOM");assertThat(m.planVersion()).isEqualTo(CustomMotionPlan.VERSION);assertThat(m.situations()).isEmpty();}
 @Test void storedLegacyReceiptResourceWithoutNewFieldsStillReads()throws Exception{
  var m=own(CustomMotionPlan.VERSION,List.of(),"pet");var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(m);node.remove("planVersion");node.remove("situations");var restored=json.treeToValue(node,OwnedCustomMotion.class);assertThat(restored.planVersion()).isEqualTo(CustomMotionPlan.VERSION);assertThat(restored.careEligible("PET")).isTrue();node.put("planVersion",CustomMotionPlan.CURRENT_VERSION);assertThatThrownBy(()->json.treeToValue(node,OwnedCustomMotion.class)).isInstanceOf(Exception.class);
 }
}
