package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Common planning contract. Production author/model wiring is explicit and has no fallback/retry. */
public final class CustomMotionPlanner {
 public static final String INSTRUCTIONS="Plan eight varied actions using frozen persona, visual identity and eligible evidence. Keep USER preferences attributed to USER; never turn them into CHARACTER facts. Preserve subject and source IDs. Shared experience requires an actual source quote. Without memory, invent persona-consistent actions as PERSONA_CREATIVE, never claim a shared memory. Exclude previously used/reserved episode sources. No fixed action categories or per-set ratios. Only basic semantics may enter room rotation/LLM allowed motions; free actions must be albumOnly. For new V2 plans provide explicit CARE6 situations only when appropriate, leave free motions album-only with no tags; never guess FEED versus SNACK from eat and do not force all eight into care. Return pose, props, staging and rationale for every action.";
 public record Context(String instructions,JsonNode persona,JsonNode legacySource,JsonNode appearance,List<JsonNode> availableMemory){}
 /** Pet languages the product writes in; anything else falls back to Korean like ZzalPet#getDiaryLanguage. */
 public static final Set<String> LANGUAGES=Set.of("ko","ja","en");
 static String language(String value){return value!=null&&LANGUAGES.contains(value)?value:"ko";}
 /** Author-facing language line. Titles are shown to the owner as-is, so they must be in the pet's language. */
 public static String languageInstruction(String language){
  String name=switch(language(language)){case "ja"->"Japanese (日本語)";case "en"->"English";default->"Korean (한국어)";};
  return "OUTPUT LANGUAGE: "+language(language)+". Write every title, action, pose text after its prefix, props, staging and rationale in "+name+". Titles are shown to the owner verbatim, so a title in any other language is rejected. Keep JSON keys, enum values and the pose prefix (standing:/crouch:/lying:) in English.";
 }
 /** Deterministic script check on titles only; null when the title matches the pet language. */
 static String languageMismatch(CustomMotionPlan plan,String language){
  String lang=language(language);
  for(var m:plan.motions()){String t=m.title();boolean hangul=t.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HANGUL);
   boolean kana=t.codePoints().anyMatch(c->{var s=Character.UnicodeScript.of(c);return s==Character.UnicodeScript.HIRAGANA||s==Character.UnicodeScript.KATAKANA;});
   boolean han=t.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN);
   boolean ok=switch(lang){case "ja"->kana||han;case "en"->!hangul&&!kana;default->hangul;};
   if(!ok)return "Motion title language does not match pet language "+lang+": order "+m.order();}
  return null;
 }
 @FunctionalInterface public interface Author {CustomMotionPlan propose(Context context)throws Exception;}
 private final ObjectMapper json=new ObjectMapper();
 /** Revalidation of an already frozen plan: no language gate, so historical plans stay valid. */
 public CustomMotionPlan plan(String snapshot,Set<String> excludedEpisodes,Author author)throws Exception {return plan(snapshot,excludedEpisodes,null,author);}
 /** New planning: when language is given the author is told to write in it and titles in another script are rejected. */
 public CustomMotionPlan plan(String snapshot,Set<String> excludedEpisodes,String language,Author author)throws Exception {
  var source=json.readTree(snapshot);if(!BundleCommandAdapter.subjectPreservingPolicy(source.path("materialPolicy").asText()))throw new IllegalArgumentException("New planning requires subject-preserving V3 materials");
  if(BundleCommandAdapter.SHEET_REFERENCE_POLICY.equals(source.path("materialPolicy").asText()))CustomGridPrompt.requireFrozenTemplate(source);
  Map<Long,JsonNode> eligible=new HashMap<>();
  for(var fact:source.path("memory").path("candidates"))if(eligible(fact)&&!excludedEpisodes.contains(provenance(fact))&&!excludedEpisodes.contains("FACT:"+fact.path("id").asLong()))eligible.put(fact.path("id").asLong(),fact);
  var appearance=json.createObjectNode();appearance.set("identity",source.path("identity").deepCopy());appearance.set("sourceImageKey",source.path("sourceImageKey").deepCopy());appearance.set("identityHash",source.path("identityHash").deepCopy());appearance.set("sheetImageKey",source.path("sheetImageKey").deepCopy());appearance.set("baseReferenceRound",source.path("baseReferenceRound").deepCopy());appearance.set("anchorsKey",source.path("anchorsKey").deepCopy());appearance.set("appearancePipelineVersion",source.path("appearancePipelineVersion").deepCopy());
  var plan=author.propose(new Context(language==null?INSTRUCTIONS:INSTRUCTIONS+" "+languageInstruction(language),source.path("persona").deepCopy(),source.path("legacySource").deepCopy(),appearance,eligible.values().stream().sorted(Comparator.comparingLong(n->n.path("id").asLong())).map(n->(JsonNode)n.deepCopy()).toList()));
  for(var motion:plan.motions())for(var evidence:motion.selectedSources()){
   var fact=eligible.get(evidence.factId());if(fact==null||fact.path("revision").asInt()!=evidence.revision()||!fact.path("subject").asText().equals(evidence.subject()))throw new IllegalArgumentException("Unknown, excluded or misattributed motion evidence");
  }
  if(BundleCommandAdapter.SHEET_REFERENCE_POLICY.equals(source.path("materialPolicy").asText()))CustomGridPrompt.validateGrounding(plan);
  if(language!=null){String mismatch=languageMismatch(plan,language);if(mismatch!=null)throw new LanguageMismatch(mismatch);}
  return plan;
 }
 /** Distinct rejection so the planning claim can be marked as a language failure, not an unknown result. */
 public static final class LanguageMismatch extends IllegalArgumentException {public LanguageMismatch(String message){super(message);}}
 static boolean eligible(JsonNode f){return f.path("id").canConvertToLong()&&f.path("revision").asInt()>0&&Set.of("USER","CHARACTER").contains(f.path("subject").asText())&&"ACTIVE".equals(f.path("status").asText())&&"USER_REPORTED".equals(f.path("kind").asText())&&f.path("sourceTurnId").canConvertToLong()&&!f.path("sourceQuote").asText().isBlank();}
 static String provenance(JsonNode f){return AttendanceService.hash(f.path("sourceTurnId").asLong()+"|"+f.path("sourceQuote").asText().strip().replaceAll("\\s+"," "));}
}
