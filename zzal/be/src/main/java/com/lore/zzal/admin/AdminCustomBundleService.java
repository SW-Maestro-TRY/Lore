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
 /** Lazy guarded detail. The list never performs these extra storage reads. */
 public CustomReviewSequence.Sequence sequence(Long userId,long jobId){
  guard.require(userId);if(jobId<1)throw new BusinessException(ErrorCode.INVALID_INPUT);
  try{
   var rows=db.queryForList("SELECT id,pet_id,state,snapshot_json,input_hash,source_plan_json,source_plan_hash,manifest_hash,execution_id FROM zzal_custom_bundle_job WHERE id=?",jobId);
   if(rows.size()!=1)return CustomReviewSequence.unavailable("UNAVAILABLE","SET_NOT_FOUND");var row=rows.getFirst();
   if(!(row.get("source_plan_json")instanceof String raw)||row.get("manifest_hash")==null)return CustomReviewSequence.unavailable("UNAVAILABLE","CORE_RESULT_PENDING");
   String snapshot=(String)row.get("snapshot_json");if(!Objects.equals(sha(raw),row.get("source_plan_hash"))||!Objects.equals(sha(snapshot),row.get("input_hash")))throw new IllegalArgumentException("Frozen source hash");
   String set=UUID.nameUUIDFromBytes((jobId+":"+sha(raw)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),prefix="images/zzal/custom/"+set;long pet=((Number)row.get("pet_id")).longValue();JsonNode frozen=parse(snapshot);int round=frozen.path("baseReferenceRound").asInt();
   String baseKey=com.lore.zzal.motion.MotionImageKeys.anchors(pet,round);if(round<1||!baseKey.equals(frozen.path("anchorsKey").asText()))throw new IllegalArgumentException("Frozen owner/round reference");
   byte[] receiptBytes=readSmall(prefix+"/manifest.json",CustomReviewSequence.MAX_MANIFEST_BYTES);if(!CustomReviewSequence.hash(receiptBytes).equals(row.get("manifest_hash")))throw new IllegalArgumentException("Core manifest hash");JsonNode receipt=json.readTree(receiptBytes),publication=receipt.path("publication");
   if(!set.equals(receipt.path("requestId").asText())||!set.equals(receipt.path("executionId").asText())||!set.equals(row.get("execution_id")))throw new IllegalArgumentException("Core set identity");
   String metadataKey=prefix+"/resource-metadata.json",customKey=prefix+"/anchors.json";if(!"CUSTOM_PUBLICATION_V1".equals(publication.path("version").asText())||!metadataKey.equals(publication.path("metadataKey").asText())||!customKey.equals(publication.path("anchorsKey").asText()))return CustomReviewSequence.unavailable("UNAVAILABLE","LEGACY_NOT_GENERATED");
   byte[] metadataBytes=readSmall(metadataKey,CustomReviewSequence.MAX_MANIFEST_BYTES);if(!CustomReviewSequence.hash(metadataBytes).equals(publication.path("metadataHash").asText()))throw new IllegalArgumentException("Core metadata hash");JsonNode metadata=json.readTree(metadataBytes),ref=metadata.path("reviewSequence");
   if(ref.isMissingNode()||ref.isNull())return CustomReviewSequence.unavailable("UNAVAILABLE","LEGACY_NOT_GENERATED");if(!CustomReviewSequence.VERSION.equals(ref.path("version").asText()))throw new IllegalArgumentException("Unknown review version");
   if("INCOMPLETE".equals(ref.path("status").asText())){String reason=ref.path("reasonCode").asText();return CustomReviewSequence.unavailable("INCOMPLETE",Set.of("SOURCE_REFERENCE_INVALID","BASIC_ASSET_UNAVAILABLE","ANCHOR_REFERENCE_MISMATCH","REVIEW_EXPORT_TIMEOUT","REVIEW_EXPORT_FAILED","REVIEW_LIMIT_EXCEEDED").contains(reason)?reason:"REVIEW_UNAVAILABLE");}
   String manifestKey=CustomReviewSequence.reviewPrefix(prefix)+"/manifest.json";if(!"READY".equals(ref.path("status").asText())||!manifestKey.equals(ref.path("manifestKey").asText()))throw new IllegalArgumentException("Review manifest path");byte[] reviewBytes=readSmall(manifestKey,CustomReviewSequence.MAX_MANIFEST_BYTES);if(!CustomReviewSequence.hash(reviewBytes).equals(ref.path("manifestSHA256").asText()))throw new IllegalArgumentException("Review manifest hash");JsonNode manifest=json.readTree(reviewBytes);
   byte[] baseBytes=readSmall(baseKey,CustomReviewSequence.MAX_MANIFEST_BYTES),customBytes=readSmall(customKey,CustomReviewSequence.MAX_MANIFEST_BYTES);if(!CustomReviewSequence.hash(baseBytes).equals(manifest.path("baseAnchorsSHA256").asText())||!CustomReviewSequence.hash(customBytes).equals(manifest.path("customAnchorsSHA256").asText())||!CustomReviewSequence.hash(customBytes).equals(publication.path("anchorsHash").asText()))throw new IllegalArgumentException("Review anchor hashes");
   Map<String,String> hashes=new HashMap<>();for(var asset:db.queryForList("SELECT asset_key,asset_hash FROM zzal_custom_bundle_asset WHERE job_id=?",jobId))hashes.put((String)asset.get("asset_key"),(String)asset.get("asset_hash"));if(hashes.size()!=8)throw new IllegalArgumentException("Eight recorded custom assets required");
   var sequence=CustomReviewSequence.validate(manifest,pet,round,prefix,json.readTree(baseBytes),json.readTree(customBytes),hashes);long total=0;for(var frame:sequence.frames()){byte[] png=readSmall(frame.imageKey(),CustomReviewSequence.MAX_PNG_BYTES);total+=png.length;if(total>64L*1024*1024)throw new IllegalArgumentException("Review size limit");CustomReviewSequence.verifyPng(frame,png);}return sequence;
  }catch(Exception invalid){return CustomReviewSequence.unavailable("INCOMPLETE","REVIEW_ASSET_INVALID");}
 }
 private byte[] readSmall(String key,int limit)throws java.io.IOException{try(var stream=s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())){byte[] raw=stream.readNBytes(limit+1);if(raw.length>limit)throw new IllegalArgumentException("Review object size");return raw;}}
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
