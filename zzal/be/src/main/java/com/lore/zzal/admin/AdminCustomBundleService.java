package com.lore.zzal.admin;
import com.fasterxml.jackson.databind.*;
import com.lore.zzal.attendance.*;
import com.lore.common.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.time.Instant;
import java.util.*;

@Service @ConditionalOnProperty(name="app.zzal.admin.enabled",havingValue="true")
public class AdminCustomBundleService {
 private final JdbcTemplate db;private final AdminGuard guard;private final S3Client s3;private final String bucket;private final ObjectMapper json=new ObjectMapper();
 public AdminCustomBundleService(JdbcTemplate db,AdminGuard guard,S3Client s3,@Value("${app.s3.content-bucket}")String bucket){this.db=db;this.guard=guard;this.s3=s3;this.bucket=bucket;}
 public record Diagnostics(String stdoutExcerpt,String stderrExcerpt,boolean truncated,boolean available){}
 public record Motion(int order,String title,String imageKey,Long assetId,boolean published,String review,String autoCheck){}
 public record Item(long jobId,long petId,String setId,String state,String stage,String errorCode,Instant failedAt,String summary,String sourceImageKey,String sheetKey,String gridKey,List<String> partialWebPKeys,List<Motion> motions,Diagnostics diagnostics){}
 public record Page(List<Item> items,Long nextCursor){}
 public Page list(Long userId,String status,Long beforeId,int limit){
  guard.require(userId);if(!Set.of("failed","all").contains(status)||limit<1||limit>50||beforeId!=null&&beforeId<1)throw new BusinessException(ErrorCode.INVALID_INPUT);
  String scope=status.equals("failed")?" AND (j.error_code IN ('CUSTOM_POSTPROCESS_CLIP','CUSTOM_POSTPROCESS_GRID_INVALID','CUSTOM_POSTPROCESS_TIMEOUT','CUSTOM_POSTPROCESS_FAILED','CUSTOM_POSTPROCESS_MEASUREMENT_AMBIGUOUS') OR (j.state='FAILED' AND j.error_code='ASSET_CHECK_FAILED'))":"";
  List<Map<String,Object>> rows=beforeId==null?db.queryForList("SELECT j.id,j.pet_id,j.state,j.error_code,j.snapshot_json,j.source_plan_json,p.source_image_key,p.sheet_image_key FROM zzal_custom_bundle_job j JOIN zzal_pet p ON p.id=j.pet_id WHERE 1=1"+scope+" ORDER BY j.id DESC LIMIT ?",limit+1):db.queryForList("SELECT j.id,j.pet_id,j.state,j.error_code,j.snapshot_json,j.source_plan_json,p.source_image_key,p.sheet_image_key FROM zzal_custom_bundle_job j JOIN zzal_pet p ON p.id=j.pet_id WHERE j.id<?"+scope+" ORDER BY j.id DESC LIMIT ?",beforeId,limit+1);
  boolean more=rows.size()>limit;var items=new ArrayList<Item>();for(var row:rows.subList(0,Math.min(limit,rows.size())))items.add(item(row));return new Page(List.copyOf(items),more?items.getLast().jobId():null);
 }
 private Item item(Map<String,Object> row){
  long id=((Number)row.get("id")).longValue(),pet=((Number)row.get("pet_id")).longValue();String state=(String)row.get("state"),error=(String)row.get("error_code");boolean failed=error!=null&&(error.startsWith("CUSTOM_POSTPROCESS_")||state.equals("FAILED")&&error.equals("ASSET_CHECK_FAILED"));
  JsonNode snapshot=parse(row.get("snapshot_json")),plan=parse(row.get("source_plan_json"));String set=null,prefix=null;
  // The request UUID derives from the exact stored plan bytes, identical to CustomGenerationRequest.from.
  if(row.get("source_plan_json")instanceof String raw&&plan.path("motionPlan").isObject()){set=UUID.nameUUIDFromBytes((id+":"+sha(raw)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();prefix="images/zzal/custom/"+set;}
  String source=ownedInput(snapshot.path("sourceImageKey").asText(null),row.get("source_image_key")),sheet=ownedInput(snapshot.path("sheetImageKey").asText(null),row.get("sheet_image_key")),grid=prefix==null?null:present(prefix+"/grid.png");
  Map<Integer,Map<String,Object>> assets=new HashMap<>();for(var a:db.queryForList("SELECT id,motion_order,asset_key,published,human_review,auto_check FROM zzal_custom_bundle_asset WHERE job_id=? ORDER BY motion_order",id))assets.put(((Number)a.get("motion_order")).intValue(),a);
  var motions=new ArrayList<Motion>();var partial=new ArrayList<String>();
  Set<Integer> seen=new HashSet<>();
  for(JsonNode m:plan.path("motionPlan").path("motions")){
   int order=m.path("order").asInt();if(order<1||order>8||!seen.add(order))continue;
   String expected=prefix==null?null:prefix+"/"+CustomGridPrompt.SLOTS.get(order-1)+".webp";
   var a=assets.get(order);boolean published=a!=null&&Boolean.TRUE.equals(a.get("published"));
   boolean matched=a!=null&&Objects.equals(expected,a.get("asset_key"));
   String key=expected==null?null:published?(matched?expected:null):present(expected);
   if(key!=null&&!published)partial.add(key);
   motions.add(new Motion(order,label(m.path("title").asText()),key,a==null?null:((Number)a.get("id")).longValue(),published,label(a==null?null:(String)a.get("human_review")),label(a==null?null:(String)a.get("auto_check"))));
  }
  JsonNode report=failed&&set!=null?report(UUID.fromString(set)):json.createObjectNode();boolean available="CUSTOM_FAILURE_V1".equals(report.path("version").asText())&&Objects.equals(set,report.path("setId").asText())&&Objects.equals(error,report.path("errorCode").asText());
  Instant at=null;if(available)try{at=Instant.parse(report.path("failedAt").asText());}catch(Exception ignored){}
  var diagnostics=new Diagnostics(available?CustomFailureEvidence.excerpt(report.path("stdoutExcerpt").asText(null)):null,available?CustomFailureEvidence.excerpt(report.path("stderrExcerpt").asText(null)):null,available&&report.path("truncated").asBoolean(),available);
  String summary=!failed?null:"ASSET_CHECK_FAILED".equals(error)?"결과 파일 검증에 실패해 확인이 필요해요.":error.endsWith("MEASUREMENT_AMBIGUOUS")?"서 있는 몸의 크기 기준이 불분명해 확인이 필요해요.":error.endsWith("TIMEOUT")?"후처리 대기 시간이 지나 확인이 필요해요.":error.endsWith("CLIP")?"후처리에서 잘림이 감지되어 확인이 필요해요.":"맞춤 후처리가 중단되어 확인이 필요해요.";
  return new Item(id,pet,set,state,failed?("ASSET_CHECK_FAILED".equals(error)?"ASSET_VALIDATION":"POSTPROCESS"):null,failed?error:null,at,summary,source,sheet,grid,List.copyOf(partial),List.copyOf(motions),diagnostics);
 }
 private JsonNode report(UUID set){try{
  // Versioned keys preserve every attempt; lexicographic timestamp order selects the first failure.
  var keys=s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(CustomFailureEvidence.prefix(set)).maxKeys(1).build()).contents();
  if(keys.isEmpty())return json.createObjectNode();
  byte[] bytes=s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(keys.getFirst().key()).build()).asByteArray();
  if(bytes.length>32768)return json.createObjectNode();return nonnull(json.readTree(bytes));
 }catch(Exception unavailable){return json.createObjectNode();}}
 private JsonNode nonnull(JsonNode node){return node==null?json.createObjectNode():node;}
 private JsonNode parse(Object value){try{return value instanceof String raw?nonnull(json.readTree(raw)):json.createObjectNode();}catch(Exception ignored){return json.createObjectNode();}}
 private String ownedInput(String frozen,Object actual){return Objects.equals(frozen,actual)?safe(frozen):null;}
 private static String sha(String raw){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
 private static String label(String raw){return raw==null?null:CustomFailureEvidence.redact(raw,120).replaceAll("[\\r\\n\\t]"," ");}
 private static String safe(String key){return key!=null&&key.startsWith("images/zzal/")&&key.length()<=300&&!key.contains("..")&&key.matches("[A-Za-z0-9/_.-]+")?key:null;}
 private String present(String key){if(key==null)return null;try{s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());return key;}catch(Exception unavailable){return null;}}
}
