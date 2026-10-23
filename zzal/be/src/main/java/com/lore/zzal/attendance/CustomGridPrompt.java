package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
/** Dynamic plans use the proven 25-mark/4x4/adjacent AB geometry, not the old fixed unlock-eight actions. */
public final class CustomGridPrompt {
 public static final String TEMPLATE_VERSION="custom-v2";
 private static String template(){try(var stream=CustomGridPrompt.class.getResourceAsStream("/zzal/prompt/custom/v2/grid.txt")){if(stream==null)throw new IllegalStateException("Custom grid template missing");return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new IllegalStateException("Cannot read custom grid template",e);}}
 public static String templateHash(){return AttendanceService.hash(template());}
 /** Physical postprocessor slots only, never inferred action semantics. Excluding base preserves seeded K/Hw. */
 public static final List<String> SLOTS=com.lore.zzal.motion.MotionCatalog.ALL.stream().filter(m->m.layer()==com.lore.zzal.motion.MotionLayer.BASIC_2).map(com.lore.zzal.motion.MotionSpec::key).toList();
 public record Render(String prompt,String postures){}
 /** No fallback to a new template for a historical version/hash. Called before any author/image request. */
 public static void requireFrozenTemplate(com.fasterxml.jackson.databind.JsonNode source){
  if(!BundleCommandAdapter.SHEET_REFERENCE_POLICY.equals(source.path("materialPolicy").asText())||!TEMPLATE_VERSION.equals(source.path("customGridTemplateVersion").asText())||!templateHash().equals(source.path("customGridTemplateHash").asText()))throw new IllegalArgumentException("Frozen sheet/template contract required; historical job is not rewritten");
  CustomPostprocessProfile.from(source,source.path("appearancePipelineVersion").asText());
  String sheet=source.path("sheetImageKey").asText();
  if(!sheet.startsWith("images/")||sheet.startsWith("images/zzal/demo/")||sheet.contains(".."))throw new IllegalArgumentException("Real frozen sheet reference required");
 }
 /** Postprocessor crouch includes seated/kneeling silhouettes; keep the authored pose untouched. */
 static String grounding(String pose){
  String prefix=pose.split(":",2)[0].strip();
  return switch(prefix){
   case "standing","crouch","lying" -> prefix;
   case "sitting","seated" -> "crouch";
   default -> throw new IllegalArgumentException("Explicit postprocess grounding required before generation");
  };
 }
 public static void validateGrounding(CustomMotionPlan plan){
  for(var motion:plan.motions())grounding(motion.pose());
 }
 public static Render render(CustomGenerationRequest request)throws Exception {
  var source=new ObjectMapper().readTree(request.frozenSnapshot());
  requireFrozenTemplate(source);
  // IdentityStep may intentionally complete with an empty paragraph; match the existing GridStep contract.
  String identity=source.path("identity").asText("").trim();
  StringBuilder prompt=new StringBuilder();
  List<String> postures=new ArrayList<>();
  for(var m:request.frozenPlan().motions().stream().sorted(Comparator.comparingInt(CustomMotionPlan.Motion::order)).toList()){
   String posture=grounding(m.pose());
   postures.add(SLOTS.get(m.order()-1)+"="+posture);
   prompt.append("Cells ").append(m.order()*2-1).append("-").append(m.order()*2).append(": ").append(new ObjectMapper().writeValueAsString(m)).append('\n');
  }
  return new Render(template().replace("{IDENT}",identity).replace("{DYNAMIC_MOTIONS}",prompt.toString()),String.join(",",postures));
 }
}
