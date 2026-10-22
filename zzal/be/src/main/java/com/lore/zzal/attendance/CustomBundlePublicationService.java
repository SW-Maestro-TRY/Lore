package com.lore.zzal.attendance;

import com.fasterxml.jackson.databind.*;
import com.lore.common.s3.S3Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Storage checks never hold database row locks. Publication and due rights commit together. */
@Service
public final class CustomBundlePublicationService {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final AttendanceService attendance;private final S3Storage storage;private final String python;private final ObjectMapper json=new ObjectMapper();
 public CustomBundlePublicationService(JdbcTemplate db,TransactionTemplate tx,AttendanceService attendance,S3Storage storage,@Value("${app.zzal.python.bin:python3}")String python){this.db=db;this.tx=tx;this.attendance=attendance;this.storage=storage;this.python=python;}
 private record Job(long petId,String snapshot,String inputHash,String sourcePlan,String sourceHash,String manifestHash,String executionId,String state){}
 private record Asset(long id,int order,String key,String hash,String auto,String review,boolean published){}
 private record Checked(Job job,List<Asset> assets,List<Map<String,Object>> sources){}
 /** Explicit call only: never generates, retries a provider, or rewrites historical evidence. */
 public int publish(long ownerId,long jobId,Instant now)throws Exception{
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Publication preflight must run outside a transaction");
  Job before=readJob(ownerId,jobId,false);List<Asset> assets=readAssets(jobId);Checked checked=check(jobId,before,assets);
  return Objects.requireNonNull(tx.execute(status->{
   // Existing attendance callers lock pet first; preserve that ordering.
   var owner=db.queryForList("SELECT id FROM zzal_pet WHERE id=? AND user_id=? FOR UPDATE",checked.job.petId,ownerId);if(owner.isEmpty())throw new IllegalStateException("Publication owner changed");
   Job current=readJob(ownerId,jobId,true);List<Asset> currentAssets=readAssets(jobId,true);
   if(!checked.job.equals(current)||!checked.sources.equals(sourceEvidence(jobId,true)))throw new IllegalStateException("Publication evidence changed after preflight");
   if(!checked.assets.equals(currentAssets)&&!(currentAssets.size()==8&&currentAssets.stream().allMatch(Asset::published)&&assetEvidence(checked.assets).equals(assetEvidence(currentAssets))))throw new IllegalStateException("Publication evidence changed after preflight");
   int count=db.update("UPDATE zzal_custom_bundle_asset SET published=true WHERE job_id=? AND published=false",jobId);
   if(count!=0&&count!=8)throw new IllegalStateException("Partial publication is not permitted");
   attendance.grantReady(current.petId,now);return count;
  }));
 }
 /** Trusted generation-job caller only; not an end-user API. Returns false before generation. */
 public boolean publishGenerated(long jobId,Instant now)throws Exception{
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Publication preflight must run outside a transaction");
  var rows=db.queryForList("SELECT p.user_id,j.state FROM zzal_custom_bundle_job j JOIN zzal_pet p ON p.id=j.pet_id WHERE j.id=?",jobId);
  if(rows.size()!=1)throw new IllegalStateException("Existing owned job required");
  var row=rows.getFirst();if(!"GENERATED".equals(row.get("state")))return false;
  publish(((Number)row.get("user_id")).longValue(),jobId,now);return true;
 }
 private Job readJob(long owner,long id,boolean lock){
  var rows=db.query("SELECT j.pet_id,j.snapshot_json,j.input_hash,j.source_plan_json,j.source_plan_hash,j.manifest_hash,j.execution_id,j.state FROM zzal_custom_bundle_job j JOIN zzal_pet p ON p.id=j.pet_id WHERE j.id=? AND p.user_id=?"+(lock?" FOR UPDATE OF j":""),(r,n)->new Job(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8)),id,owner);
  if(rows.size()!=1)throw new IllegalStateException("Owned generated job required");return rows.getFirst();
 }
 private List<Asset> readAssets(long id){return readAssets(id,false);}
 private List<Asset> readAssets(long id,boolean lock){return db.query("SELECT id,motion_order,asset_key,asset_hash,auto_check,human_review,published FROM zzal_custom_bundle_asset WHERE job_id=? ORDER BY motion_order"+(lock?" FOR UPDATE":""),(r,n)->new Asset(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBoolean(7)),id);}
 private Checked check(long jobId,Job job,List<Asset> assets)throws Exception{
  if(!"GENERATED".equals(job.state)||job.sourcePlan==null||!AttendanceService.hash(job.snapshot).equals(job.inputHash)||!AttendanceService.hash(job.sourcePlan).equals(job.sourceHash)||job.manifestHash==null)throw new IllegalStateException("Immutable generated evidence required");
  var request=CustomGenerationRequest.from(jobId,job.snapshot,job.sourcePlan);
  var source=json.readTree(job.snapshot);CustomGridPrompt.requireFrozenTemplate(source);var profile=CustomPostprocessProfile.from(source,source.path("appearancePipelineVersion").asText());
  if(assets.size()!=8||assets.stream().anyMatch(a->!"PASS".equals(a.auto)||"REJECTED".equals(a.review)))throw new IllegalStateException("Eight checked non-rejected assets required");
  long published=assets.stream().filter(Asset::published).count();if(published!=0&&published!=8)throw new IllegalStateException("Historical partial publication is not rewritten");
  // Asset prefix is bound to the frozen request UUID, never selected by a caller-provided object key.
  String key=assets.getFirst().key;int slash=key.lastIndexOf('/');if(slash<0)throw new IllegalStateException("Invalid asset prefix");String prefix=key.substring(0,slash);
  Path temp=Files.createTempDirectory("zzal-publication-");
  try{
   Path manifestFile=download(prefix+"/manifest.json",temp.resolve("manifest.json"));String raw=Files.readString(manifestFile);if(!AttendanceService.hash(raw).equals(job.manifestHash))throw new IllegalStateException("Manifest hash mismatch");
   var manifest=json.readValue(raw,BundleJobWorker.Manifest.class);var frozenPlan=json.treeToValue(json.readTree(job.sourcePlan).get("motionPlan"),CustomMotionPlan.class);
   var evidence=manifest.publication();if(evidence==null||!"CUSTOM_PUBLICATION_V1".equals(evidence.version()))throw new IllegalStateException("Versioned publication evidence required");
   if(!prefix.equals("images/zzal/custom/"+manifest.requestId())||!Objects.equals(job.executionId,manifest.executionId())||!manifest.requestId().equals(manifest.executionId()))throw new IllegalStateException("Set identity mismatch");
   BundleJobWorker.validateExecutionReceipt(request,manifest);
   Set<BundleSourcePlan.Source> used=new HashSet<>(manifest.usedSources());if(used.size()!=manifest.usedSources().size()||!new HashSet<>(frozenPlan.selectedSources()).containsAll(used))throw new IllegalStateException("Material receipt mismatch");
   var sources=sourceEvidence(jobId);Set<BundleSourcePlan.Source> recorded=new HashSet<>();for(var row:sources){String state=(String)row.get("state");if("RESERVED".equals(state))throw new IllegalStateException("Unresolved material reservation");if("USED".equals(state))recorded.add(new BundleSourcePlan.Source(((Number)row.get("fact_id")).longValue(),((Number)row.get("fact_revision")).intValue()));}if(!used.equals(recorded))throw new IllegalStateException("Used material ledger mismatch");
   exact(prefix+"/resource-metadata.json",evidence.metadataKey());exact(prefix+"/anchors.json",evidence.anchorsKey());
   JsonNode metadata=readVerified(evidence.metadataKey(),evidence.metadataHash(),temp.resolve("resource-metadata.json"));JsonNode anchors=readVerified(evidence.anchorsKey(),evidence.anchorsHash(),temp.resolve("anchors.json"));
   if(CustomMotionPlan.CURRENT_VERSION.equals(frozenPlan.version())&&!metadata.has("planVersion")||metadata.has("planVersion")&&!frozenPlan.version().equals(metadata.path("planVersion").asText()))throw new IllegalStateException("Frozen plan version mismatch");
   if(!manifest.requestId().equals(metadata.path("setId").asText())||profile.baseReferenceRound()!=metadata.path("baseReferenceRound").asInt()||!profile.anchorsKey().equals(metadata.path("baseAnchorsKey").asText())||!profile.appearanceVersion().equals(metadata.path("appearancePipelineVersion").asText())||!evidence.anchorsKey().equals(metadata.path("anchorsKey").asText())||metadata.path("resources").size()!=8)throw new IllegalStateException("Resource metadata identity mismatch");
   String baseRaw=Files.readString(download(profile.anchorsKey(),temp.resolve("base-anchors.json")));
   JsonNode baseAnchors=json.readTree(baseRaw);
   String gridHash=null;
   if(anchors.has("customTransform")||metadata.has("customTransform")){
    exact(prefix+"/grid.png",metadata.path("gridImageKey").asText());
    gridHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(download(prefix+"/grid.png",temp.resolve("grid.png")))));
   }
   CustomCanvasTransform.validate(baseAnchors,anchors,metadata,AttendanceService.hash(baseRaw),gridHash);
   validateAnchors(anchors,frozenPlan);
   var verifier=new BundleAssetVerifier(python);verifier.preflight();
   if(manifest.assets()==null||manifest.assets().size()!=8)throw new IllegalStateException("Eight manifest assets required");
   for(int i=0;i<8;i++){
    Asset asset=assets.get(i);String slot=CustomGridPrompt.SLOTS.get(i);var motion=frozenPlan.motions().stream().filter(m->m.order()==asset.order).findFirst().orElseThrow();
    var declared=manifest.assets().stream().filter(a->a.motionOrder()==asset.order).findFirst().orElseThrow();
    if(asset.order!=i+1||!asset.key.equals(prefix+"/"+slot+".webp")||!asset.key.equals(declared.assetKey())||!asset.hash.equals(declared.sha256())||declared.published()||"REJECTED".equals(declared.humanReview()))throw new IllegalStateException("Asset receipt mismatch");
    JsonNode resource=metadata.path("resources").get(i);if(resource==null)throw new IllegalStateException("Missing resource metadata");
    if(CustomMotionPlan.CURRENT_VERSION.equals(frozenPlan.version())&&!resource.has("situations")||resource.has("situations")&&!json.valueToTree(motion.situations()).equals(resource.get("situations")))throw new IllegalStateException("Frozen situation metadata mismatch");if(resource==null||!resource.path("motionOrder").isIntegralNumber()||!resource.has("semantic")||!resource.path("albumOnly").isBoolean()||!resource.path("embeddedProps").isBoolean()||!resource.path("suppressRoomProps").isBoolean())throw new IllegalStateException("Explicit resource metadata required");if(resource.path("motionOrder").asInt()!=asset.order||!slot.equals(resource.path("resourceSlotKey").asText())||!slot.equals(resource.path("anchorPoseKey").asText())||!Objects.equals(motion.semantic(),resource.path("semantic").isNull()?null:resource.path("semantic").asText())||motion.albumOnly()!=resource.path("albumOnly").asBoolean()||(!motion.props().isEmpty())!=resource.path("embeddedProps").asBoolean()||!resource.path("suppressRoomProps").asBoolean())throw new IllegalStateException("Frozen motion metadata mismatch");
    String local="motion-"+asset.order+".webp";download(asset.key,temp.resolve(local));verifier.verifyCanvas(new BundleAssetVerifier.Input(asset.order,local,asset.key,asset.hash,asset.review,false),temp,anchors.path("canvas").path("w").asInt(),anchors.path("canvas").path("h").asInt());
    if(anchors.has("customTransform"))verifier.verifyCustomFrames(temp.resolve(local),anchors.get("customTransform"),asset.order);
   }
   return new Checked(job,assets,sources);
  }finally{try(var walk=Files.walk(temp)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 }
 private static List<Asset> assetEvidence(List<Asset> assets){return assets.stream().map(a->new Asset(a.id,a.order,a.key,a.hash,a.auto,a.review,false)).toList();}
 private List<Map<String,Object>> sourceEvidence(long id){return sourceEvidence(id,false);}
 private List<Map<String,Object>> sourceEvidence(long id,boolean lock){return db.queryForList("SELECT fact_id,fact_revision,state,provenance_key FROM zzal_custom_bundle_source WHERE job_id=? ORDER BY fact_id,fact_revision"+(lock?" FOR UPDATE":""),id);}
 private Path download(String key,Path target)throws Exception{if(key==null||!key.startsWith("images/")||key.contains(".."))throw new IllegalArgumentException("Invalid storage key");storage.download(key,target);if(!Files.isRegularFile(target))throw new IllegalStateException("Missing storage evidence");return target;}
 private JsonNode readVerified(String key,String hash,Path path)throws Exception{download(key,path);String raw=Files.readString(path);if(hash==null||!AttendanceService.hash(raw).equals(hash))throw new IllegalStateException("Sidecar hash mismatch");return json.readTree(raw);}
 private static void exact(String expected,String actual){if(!expected.equals(actual))throw new IllegalStateException("Unexpected evidence key");}
 private static void validateAnchors(JsonNode anchors,CustomMotionPlan plan){
  if(!anchors.path("canvas").path("w").isIntegralNumber()||!anchors.path("canvas").path("h").isIntegralNumber()||!"1".equals(anchors.path("version").asText())||!positive(anchors.path("canvas").path("w"))||!positive(anchors.path("canvas").path("h"))||!positive(anchors.path("K"))||!positive(anchors.path("Hw")))throw new IllegalStateException("Base anchor scale required");
  for(String slot:CustomGridPrompt.SLOTS){var p=anchors.path("poses").path(slot);for(String field:List.of("bbox","head_top","head_side","hand_front","feet"))if(!p.path(field).isObject())throw new IllegalStateException("Missing pose anchors");for(String field:List.of("x","y","w","h"))if(!finite(p.path("bbox").path(field)))throw new IllegalStateException("Invalid pose bbox");if(!positive(p.path("bbox").path("w"))||!positive(p.path("bbox").path("h")))throw new IllegalStateException("Invalid pose extent");for(String field:List.of("head_top","head_side","hand_front","feet"))if(!finite(p.path(field).path("y")))throw new IllegalStateException("Invalid pose coordinate");if(!finite(p.path("head_top").path("x"))||!finite(p.path("feet").path("center_x")))throw new IllegalStateException("Invalid pose coordinate");for(String field:List.of("head_side","hand_front","feet"))if(!finite(p.path(field).path("left_x"))||!finite(p.path(field).path("right_x")))throw new IllegalStateException("Invalid pose span");}
 }
 private static boolean finite(JsonNode value){return value.isNumber()&&Double.isFinite(value.doubleValue());}
 private static boolean positive(JsonNode value){return finite(value)&&value.doubleValue()>0;}
}
