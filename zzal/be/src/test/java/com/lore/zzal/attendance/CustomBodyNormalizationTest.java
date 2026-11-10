package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CustomBodyNormalizationTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode fixture(String name)throws Exception{String rel="zzal/custom-body-"+name+".json";var in=getClass().getResourceAsStream("/"+rel);return(ObjectNode)(in==null?json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("zzal/be/src/test/resources/"+rel))):json.readTree(in));}
 ObjectNode proof(ObjectNode f){return(ObjectNode)f.path("anchors").path("customTransform").path("bodyNormalization");}
 void sync(ObjectNode f){((ObjectNode)f.get("metadata")).set("customTransform",f.path("anchors").path("customTransform").deepCopy());}
 void validate(ObjectNode f)throws Exception{CustomCanvasTransform.validate(f.get("base"),f.get("anchors"),f.get("metadata"),f.path("baseHash").asText(),f.path("gridHash").asText(),json.treeToValue(f.get("plan"),CustomMotionPlan.class));}
 @Test void acceptsBothActualFrozenPlanOutputsAndCanonicalPlanDigests()throws Exception{for(String name:new String[]{"shane","romi"}){var f=fixture(name);assertDoesNotThrow(()->validate(f));assertFalse(proof(f).path("characterOnlySegmentation").asBoolean());}}
 @Test void requiresActualPlanForNewBodyProofWithoutWeakeningHistoricalOverload()throws Exception{var f=fixture("romi");assertThrows(IllegalStateException.class,()->CustomCanvasTransform.validate(f.get("base"),f.get("anchors"),f.get("metadata"),f.path("baseHash").asText(),f.path("gridHash").asText()));}
 @Test void rejectsFrozenPlanAndSelectionFrameTampering()throws Exception{
  for(String field:new String[]{"motionPlanSHA256","selectedOrders","selectedFrames","height","sourceHeight"}){var f=fixture("shane");var p=proof(f);switch(field){case "motionPlanSHA256"->p.put(field,"0".repeat(64));case "selectedOrders"->((ArrayNode)p.get(field)).set(0,IntNode.valueOf(7));case "selectedFrames"->((ArrayNode)p.get(field)).set(0,IntNode.valueOf(13));case "height"->((ObjectNode)p.path("measuredFrames").get(0)).put("height",999);default->p.put(field,999);}sync(f);assertThrows(IllegalStateException.class,()->validate(f),field);}
 }
 @Test void rejectsChangedScaleAndClaimsOfAnatomicalSegmentation()throws Exception{var f=fixture("romi");((ObjectNode)f.path("anchors").path("customTransform")).put("normalizationScale",1);sync(f);assertThrows(IllegalStateException.class,()->validate(f));var g=fixture("romi");proof(g).put("characterOnlySegmentation",true);sync(g);assertThrows(IllegalStateException.class,()->validate(g));}
}
