package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Common planning contract. Production author/model wiring is explicit and has no fallback/retry. */
public final class CustomMotionPlanner {
 public static final String INSTRUCTIONS="Plan eight varied actions using frozen persona, visual identity and eligible evidence. Keep USER preferences attributed to USER; never turn them into CHARACTER facts. Preserve subject and source IDs. Shared experience requires an actual source quote. Without memory, invent persona-consistent actions as PERSONA_CREATIVE, never claim a shared memory. Exclude previously used/reserved episode sources. No fixed action categories or per-set ratios. Only basic semantics may enter room rotation/LLM allowed motions; free actions must be albumOnly. For new V2 plans provide explicit CARE6 situations only when appropriate, leave free motions album-only with no tags; never guess FEED versus SNACK from eat and do not force all eight into care. Return pose, props, staging and rationale for every action.";
 public record Context(String instructions,JsonNode persona,JsonNode legacySource,JsonNode appearance,List<JsonNode> availableMemory){}
 @FunctionalInterface public interface Author {CustomMotionPlan propose(Context context)throws Exception;}
 private final ObjectMapper json=new ObjectMapper();
 public CustomMotionPlan plan(String snapshot,Set<String> excludedEpisodes,Author author)throws Exception {
  var source=json.readTree(snapshot);if(!BundleCommandAdapter.subjectPreservingPolicy(source.path("materialPolicy").asText()))throw new IllegalArgumentException("New planning requires subject-preserving V3 materials");
  if(BundleCommandAdapter.SHEET_REFERENCE_POLICY.equals(source.path("materialPolicy").asText()))CustomGridPrompt.requireFrozenTemplate(source);
  Map<Long,JsonNode> eligible=new HashMap<>();
  for(var fact:source.path("memory").path("candidates"))if(eligible(fact)&&!excludedEpisodes.contains(provenance(fact))&&!excludedEpisodes.contains("FACT:"+fact.path("id").asLong()))eligible.put(fact.path("id").asLong(),fact);
  var appearance=json.createObjectNode();appearance.set("identity",source.path("identity").deepCopy());appearance.set("sourceImageKey",source.path("sourceImageKey").deepCopy());appearance.set("identityHash",source.path("identityHash").deepCopy());appearance.set("sheetImageKey",source.path("sheetImageKey").deepCopy());appearance.set("baseReferenceRound",source.path("baseReferenceRound").deepCopy());appearance.set("anchorsKey",source.path("anchorsKey").deepCopy());appearance.set("appearancePipelineVersion",source.path("appearancePipelineVersion").deepCopy());
  var plan=author.propose(new Context(INSTRUCTIONS,source.path("persona").deepCopy(),source.path("legacySource").deepCopy(),appearance,eligible.values().stream().sorted(Comparator.comparingLong(n->n.path("id").asLong())).map(n->(JsonNode)n.deepCopy()).toList()));
  for(var motion:plan.motions())for(var evidence:motion.selectedSources()){
   var fact=eligible.get(evidence.factId());if(fact==null||fact.path("revision").asInt()!=evidence.revision()||!fact.path("subject").asText().equals(evidence.subject()))throw new IllegalArgumentException("Unknown, excluded or misattributed motion evidence");
  }
  return plan;
 }
 static boolean eligible(JsonNode f){return f.path("id").canConvertToLong()&&f.path("revision").asInt()>0&&Set.of("USER","CHARACTER").contains(f.path("subject").asText())&&"ACTIVE".equals(f.path("status").asText())&&"USER_REPORTED".equals(f.path("kind").asText())&&f.path("sourceTurnId").canConvertToLong()&&!f.path("sourceQuote").asText().isBlank();}
 static String provenance(JsonNode f){return AttendanceService.hash(f.path("sourceTurnId").asLong()+"|"+f.path("sourceQuote").asText().strip().replaceAll("\\s+"," "));}
}
