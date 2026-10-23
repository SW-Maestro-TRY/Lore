package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomCanvasTransformTest {
 private final ObjectMapper json=new ObjectMapper();
 private ObjectNode fixture()throws Exception{
  var resource=getClass().getResourceAsStream("/zzal/custom-canvas-v1-fixture.json");
  if(resource==null)return (ObjectNode)json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("zzal/be/src/test/resources/zzal/custom-canvas-v1-fixture.json")));
  try(var in=resource){return (ObjectNode)json.readTree(in);}
 }
 private void validate(ObjectNode f){CustomCanvasTransform.validate(f.get("base"),f.get("anchors"),f.get("metadata"),f.path("baseHash").asText(),f.path("gridHash").asText());}
 private ObjectNode transform(ObjectNode f){return (ObjectNode)f.path("anchors").path("customTransform");}
 private void sync(ObjectNode f){((ObjectNode)f.get("metadata")).set("customTransform",transform(f).deepCopy());}
 @Test void acceptsActualExpandedSixteenFrameEvidence()throws Exception{var f=fixture();assertDoesNotThrow(()->validate(f));assertEquals(398,f.path("anchors").path("canvas").path("w").asInt());assertEquals(475,f.path("anchors").path("canvas").path("h").asInt());}
 @Test void acceptsHistoricalNoTransformWithOriginalStrictCanvas()throws Exception{var f=fixture();f.set("anchors",f.get("base").deepCopy());f.set("metadata",json.createObjectNode());assertDoesNotThrow(()->validate(f));}
 @Test void rejectsHistoricalCanvasChangeWithoutVersionedProof()throws Exception{var f=fixture();((ObjectNode)f.get("anchors")).remove("customTransform");f.set("metadata",json.createObjectNode());assertThrows(IllegalStateException.class,()->validate(f));}
 @Test void rejectsDigestScaleAndAlphaLossTampering()throws Exception{
  for(String field:new String[]{"baseAnchorsSHA256","gridSHA256","normalizationScale","lostAlphaPixels"}){
   var f=fixture();var t=transform(f);if(field.endsWith("SHA256"))t.put(field,"0".repeat(64));else if(field.equals("normalizationScale"))t.put(field,1.0);else t.put(field,1);sync(f);assertThrows(IllegalStateException.class,()->validate(f),field);
  }
 }
 @Test void rejectsDeletionOfComponentInsideOwnedCell()throws Exception{var f=fixture();var member=(ObjectNode)transform(f).path("cellMembership").get(0);var box=member.path("ownCell");var component=((ArrayNode)member.get("componentsRemoved")).addObject();component.put("reason","DISCONNECTED_OUTSIDE_OWN_CELL");component.put("pixels",1);component.putArray("bbox").add(box.get(0).asInt()+1).add(box.get(1).asInt()+1).add(box.get(0).asInt()+2).add(box.get(1).asInt()+2);member.put("removedAlphaPixels",member.path("removedAlphaPixels").asInt()+1);sync(f);assertThrows(IllegalStateException.class,()->validate(f));}
 @Test void rejectsSidecarMismatchAndOutOfCanvasEvidence()throws Exception{var mismatch=fixture();((ObjectNode)mismatch.path("metadata").path("customTransform")).put("version","FAKE");assertThrows(IllegalStateException.class,()->validate(mismatch));var bounds=fixture();((ArrayNode)transform(bounds).path("frames").get(0).path("bbox")).set(0,IntNode.valueOf(-1));sync(bounds);assertThrows(IllegalStateException.class,()->validate(bounds));}
 @Test void rejectsNonFiniteAndUnboundedCoordinates()throws Exception{var f=fixture();((ObjectNode)transform(f).path("sharedAffine")).put("dx",1000000);sync(f);assertThrows(IllegalStateException.class,()->validate(f));var unknown=fixture();transform(unknown).put("version","CUSTOM_CANVAS_V2");sync(unknown);assertThrows(IllegalStateException.class,()->validate(unknown));}
 @Test void rejectsIndependentFootAndHorizontalAffineTampering()throws Exception{
  for(String field:new String[]{"dy","standingFootTarget","dx"}){var f=fixture();var t=transform(f);if(field.equals("standingFootTarget"))t.put(field,t.path(field).asDouble()+1);else{var affine=(ObjectNode)t.path("sharedAffine");affine.put(field,affine.path(field).asInt()+1);}sync(f);assertThrows(IllegalStateException.class,()->validate(f),field);}
 }
 @Test void rejectsReversedAnchorSpans()throws Exception{var f=fixture();var point=(ObjectNode)f.path("anchors").path("poses").path(CustomGridPrompt.SLOTS.get(0)).path("head_side");point.put("left_x",200);point.put("right_x",100);assertThrows(IllegalStateException.class,()->validate(f));}
 @Test void rejectsClaimedEmptyEdgeWithBboxOutsideSafetyMargin()throws Exception{var f=fixture();((ArrayNode)transform(f).path("frames").get(0).path("bbox")).set(0,IntNode.valueOf(15));sync(f);assertThrows(IllegalStateException.class,()->validate(f));}
 // Regression from staging synthetic pet11/job1 (2026-10-24): crown49.1-Hw144/2=-22.9.
 @Test void acceptsCanonicalHeadSideProxyBeyondEitherCanvasEdge()throws Exception{
  var f=fixture();var pose=(ObjectNode)f.path("anchors").path("poses").path("eat_rice");
  ((ObjectNode)pose.path("head_side")).put("left_x",-22.9);
  assertDoesNotThrow(()->validate(f));
  double w=f.path("anchors").path("canvas").path("w").asDouble(),half=f.path("base").path("Hw").asDouble()/2;
  ((ObjectNode)pose.path("head_side")).put("right_x",w+half);
  assertDoesNotThrow(()->validate(f));
 }
 @Test void rejectsHeadSideProxyBeyondFrozenHwOrNonFinite()throws Exception{
  for(boolean right:new boolean[]{false,true}){
   var f=fixture();double half=f.path("base").path("Hw").asDouble()/2,w=f.path("anchors").path("canvas").path("w").asDouble();
   var point=(ObjectNode)f.path("anchors").path("poses").path("eat_rice").path("head_side");
   point.put(right?"right_x":"left_x",right?w+half+.1:-half-.1);
   assertThrows(IllegalStateException.class,()->validate(f));
   point.put(right?"right_x":"left_x",Double.NaN);
   assertThrows(IllegalStateException.class,()->validate(f));
  }
 }
 @Test void keepsNonProxyAnchorAndBboxBoundsStrict()throws Exception{
  for(String field:new String[]{"head_top","hand_front","feet","head_side","bbox"}){
   var f=fixture();var point=(ObjectNode)f.path("anchors").path("poses").path("eat_rice").path(field);
   point.put(field.equals("head_top")||field.equals("bbox")?"x":field.equals("head_side")?"y":"left_x",-1);
   assertThrows(IllegalStateException.class,()->validate(f),field);
  }
 }
}
