package com.lore.zzal.attendance;
import java.util.*;
/** Provider-neutral immutable execution contract. Image quality is separate from reasoning effort. */
public record CustomGenerationRequest(String pipelineVersion,UUID requestId,String quality,String snapshotHash,String sourcePlanHash,CustomMotionPlan frozenPlan,String frozenSnapshot,String frozenSourcePlan) {
 public static CustomGenerationRequest from(long jobId,String snapshot,String sourcePlan)throws Exception {
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var plan=mapper.readTree(sourcePlan).get("motionPlan");
  if(plan==null)throw new IllegalArgumentException("Typed motion plan required");
  String hash=AttendanceService.hash(sourcePlan);
  return new CustomGenerationRequest(BundleCommandAdapter.SHEET_REFERENCE_POLICY.equals(mapper.readTree(snapshot).path("materialPolicy").asText())?SHEET_PIPELINE:PIPELINE,UUID.nameUUIDFromBytes((jobId+":"+hash).getBytes(java.nio.charset.StandardCharsets.UTF_8)),"medium",AttendanceService.hash(snapshot),hash,mapper.treeToValue(plan,CustomMotionPlan.class),snapshot,sourcePlan);
 }
 public static final String SHEET_PIPELINE="CUSTOM_BUNDLE_SHEET_REFERENCE_V2";
 public static final String PIPELINE="CUSTOM_BUNDLE_GENERATION_V1";
 public CustomGenerationRequest {
  Objects.requireNonNull(requestId);Objects.requireNonNull(frozenPlan);
  if(!(PIPELINE.equals(pipelineVersion)||SHEET_PIPELINE.equals(pipelineVersion))||!"medium".equals(quality)||!AttendanceService.hash(frozenSnapshot).equals(snapshotHash)||!AttendanceService.hash(frozenSourcePlan).equals(sourcePlanHash))throw new IllegalArgumentException("Invalid immutable generation request");
  try{var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var expected=mapper.readTree(frozenSourcePlan).get("motionPlan");
   // Normalize only the newly additive absent V1 field in an in-memory comparison copy; frozen bytes/hash remain unchanged.
   if(CustomMotionPlan.VERSION.equals(frozenPlan.version())&&expected!=null){expected=expected.deepCopy();for(var m:expected.path("motions"))if(!m.has("situations"))((com.fasterxml.jackson.databind.node.ObjectNode)m).putArray("situations");}
   if(!mapper.readTree(mapper.writeValueAsString(frozenPlan)).equals(expected))throw new IllegalArgumentException("Motion plan differs from frozen source plan");
   boolean sheetPolicy=BundleCommandAdapter.SHEET_REFERENCE_POLICY.equals(mapper.readTree(frozenSnapshot).path("materialPolicy").asText());
   if(sheetPolicy!=SHEET_PIPELINE.equals(pipelineVersion))throw new IllegalArgumentException("Snapshot and generation pipeline version differ");
   var selected=new java.util.HashSet<BundleSourcePlan.Source>();
   for(var ref:mapper.readTree(frozenSourcePlan).path("selectedSources"))selected.add(new BundleSourcePlan.Source(ref.path("factId").asLong(),ref.path("revision").asInt()));
   if(!selected.equals(new java.util.HashSet<>(frozenPlan.selectedSources())))throw new IllegalArgumentException("Source plan differs from motion evidence");
   new CustomMotionPlanner().plan(frozenSnapshot,java.util.Set.of(),context->frozenPlan);
  }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Invalid frozen plan",e);}
 }
}
