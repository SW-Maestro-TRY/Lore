package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.generation.client.PostProcessor;
/** Reuse existing BASIC_2 physical slots; do not treat old fixed action names as custom semantics. */
public record CustomPostprocessProfile(String sheetImageKey,String anchorsKey,int baseReferenceRound,String appearanceVersion) {
 public static CustomPostprocessProfile from(JsonNode source,String configuredVersion){
  if(!BundleCommandAdapter.SHEET_REFERENCE_POLICY.equals(source.path("materialPolicy").asText()))throw new IllegalArgumentException("Frozen V4 appearance required; historical snapshot unchanged");
  String sheet=source.path("sheetImageKey").asText(),anchors=source.path("anchorsKey").asText(),version=source.path("appearancePipelineVersion").asText();int round=source.path("baseReferenceRound").asInt();
  if(sheet.isBlank()||anchors.isBlank()||round<1||!"v1".equals(version)||!version.equals(configuredVersion))throw new IllegalArgumentException("Missing or incompatible frozen sheet/anchors/base round/version");
  if(CustomGridPrompt.SLOTS.size()!=8||CustomGridPrompt.SLOTS.contains("base"))throw new IllegalStateException("Custom slots cannot replace basic anchor reference");
  return new CustomPostprocessProfile(sheet,anchors,round,version);
 }
 public void seed(PostProcessor.Session session)throws Exception{session.seedAnchors(anchorsKey);}
 public void split(PostProcessor.Session session,String grid,String postures)throws Exception{session.split(grid,CustomGridPrompt.SLOTS,postures,2);}
}
