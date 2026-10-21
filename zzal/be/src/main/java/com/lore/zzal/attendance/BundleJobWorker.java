package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
@Service
public class BundleJobWorker {
 public record PublicationEvidence(String version,String metadataKey,String metadataHash,String anchorsKey,String anchorsHash){}
 public record Manifest(String executionId,List<BundleAssetVerifier.Input> assets,List<BundleSourcePlan.Source> usedSources,String requestId,String pipelineVersion,String requestedQuality,String actualQuality,String provider,String model,PublicationEvidence publication){
  public Manifest(String executionId,List<BundleAssetVerifier.Input> assets,List<BundleSourcePlan.Source> usedSources,String requestId,String pipelineVersion,String requestedQuality,String actualQuality,String provider,String model){this(executionId,assets,usedSources,requestId,pipelineVersion,requestedQuality,actualQuality,provider,model,null);}
 }
 private final JdbcTemplate db;private final TransactionTemplate tx;private final AttendanceService attendance;private final String python;private final boolean enabled;
 private final BundleSourcePlan plans;
 private final ObjectMapper json=new ObjectMapper();
 public BundleJobWorker(JdbcTemplate db,TransactionTemplate tx,AttendanceService attendance,BundleSourcePlan plans,@Value("${app.zzal.python.bin:python3}")String python,@Value("${app.zzal.attendance.cli-enabled:false}")boolean enabled){this.db=db;this.plans=plans;this.tx=tx;this.attendance=attendance;this.python=python;this.enabled=enabled;}
 /** Dry run cannot claim, invoke the CLI or grant. */
 public List<Map<String,Object>> dryRun(){return db.queryForList("SELECT id,pet_id,input_hash,state FROM zzal_custom_bundle_job ORDER BY id");}
 /** A reserved dispatch token cannot be taken by a different explicit caller before paid planning. */
 public void requireCompatibleClaim(long jobId,String token){
  if(token==null||token.isBlank()||token.length()>100)throw new IllegalArgumentException("Invalid claim token");
  String reserved=db.queryForObject("SELECT claim_token FROM zzal_custom_bundle_job WHERE id=?",String.class,jobId);
  if(reserved!=null&&!reserved.equals(token))throw new IllegalStateException("Custom job is reserved by another claim token");
 }
 public boolean run(long jobId,String token,List<String> argv,Path output)throws Exception{return runInternal(jobId,token,argv,null,output);}
 public boolean run(long jobId,String token,GenerationExecutor executor,Path output)throws Exception{return runInternal(jobId,token,null,Objects.requireNonNull(executor),output);}
 private boolean runInternal(long jobId,String token,List<String> argv,GenerationExecutor executor,Path output)throws Exception{
  if(!enabled)throw new IllegalStateException("Attendance CLI execution is disabled");
  if(token==null||token.isBlank()||token.length()>100)throw new IllegalArgumentException("Invalid claim token");
  String snapshot=db.queryForObject("SELECT snapshot_json FROM zzal_custom_bundle_job WHERE id=?",String.class,jobId);
  String plan=plans.requirePlan(jobId,snapshot);
  boolean typed=plan!=null&&json.readTree(plan).has("motionPlan");
  if(!typed&&executor!=null)throw new IllegalArgumentException("Provider-neutral execution requires typed plan");
  new BundleAssetVerifier(python).preflight();
  boolean claimed=Boolean.TRUE.equals(tx.execute(s->db.update("UPDATE zzal_custom_bundle_job SET state='CLAIMED',claim_token=?,output_dir=?,cli_started_at=? WHERE id=? AND state='PENDING' AND (claim_token IS NULL OR claim_token=?)",token,output.toAbsolutePath().normalize().toString(),java.sql.Timestamp.from(Instant.now()),jobId,token)==1));
  if(!claimed)return false;
  // Any uncertain execution remains CLAIMED. Never automatically release or replay a paid claim.
  String receipt=typed?(executor==null?new CliGenerationExecutor(argv):executor).execute(CustomGenerationRequest.from(jobId,snapshot,plan),output):new BundleCommandAdapter().executePlanned(argv,snapshot,plan,output,Duration.ofMinutes(10));
  complete(jobId,token,receipt,output,Instant.now());return true;
 }
 public static void validateExecutionReceipt(CustomGenerationRequest request,Manifest receipt){
  if(receipt.publication()!=null&&receipt.assets()!=null&&receipt.assets().stream().anyMatch(BundleAssetVerifier.Input::published))throw new IllegalArgumentException("Publication requires separate validation");
  if(!request.requestId().toString().equals(receipt.requestId())||!request.pipelineVersion().equals(receipt.pipelineVersion())||!request.quality().equals(receipt.requestedQuality())||!"medium".equals(receipt.actualQuality())||receipt.provider()==null||receipt.provider().isBlank()||receipt.model()==null||receipt.model().isBlank())throw new IllegalArgumentException("Execution quality/provider attestation missing or mismatched");
 }
 public void complete(long jobId,String token,String receipt,Path output,Instant now)throws Exception{
  Manifest manifest=json.readValue(receipt,Manifest.class);
  if(manifest.executionId()==null||manifest.executionId().isBlank()||manifest.assets()==null||manifest.assets().size()!=8)throw new IllegalArgumentException("Expected execution receipt and eight assets");
  String hash=AttendanceService.hash(receipt);
  var existing=db.queryForMap("SELECT state,claim_token,manifest_hash FROM zzal_custom_bundle_job WHERE id=?",jobId);
  if(!Objects.equals(existing.get("claim_token"),token))throw new IllegalStateException("Claim token mismatch");
  if(("GENERATED".equals(existing.get("state"))||"FAILED".equals(existing.get("state")))&&existing.get("manifest_hash")!=null){if(!hash.equals(existing.get("manifest_hash")))throw new IllegalStateException("Receipt conflict");return;}
  if(!"CLAIMED".equals(existing.get("state")))throw new IllegalStateException("Job not claimed");
  String snapshot=db.queryForObject("SELECT snapshot_json FROM zzal_custom_bundle_job WHERE id=?",String.class,jobId);
  String frozen=plans.requirePlan(jobId,snapshot);
  if(frozen!=null&&json.readTree(frozen).has("motionPlan"))validateExecutionReceipt(CustomGenerationRequest.from(jobId,snapshot,frozen),manifest);
  Set<Integer> orders=new HashSet<>();var verifier=new BundleAssetVerifier(python);
  Map<Integer,String> checks=new HashMap<>();
  for(var asset:manifest.assets()){
   if(asset.motionOrder()<1||asset.motionOrder()>8||!orders.add(asset.motionOrder())||asset.sha256()==null||!asset.sha256().matches("[a-f0-9]{64}")||asset.assetKey()==null||!asset.assetKey().startsWith("images/")||asset.assetKey().contains("..")||!("UNREVIEWED".equals(asset.humanReview())||"APPROVED".equals(asset.humanReview())||"REJECTED".equals(asset.humanReview())))throw new IllegalArgumentException("Invalid manifest asset");
   try{verifier.verify(asset,output);checks.put(asset.motionOrder(),"PASS");}
   catch(Exception failed){checks.put(asset.motionOrder(),"FAIL");}
  }
  boolean failed=checks.containsValue("FAIL");
  tx.executeWithoutResult(s->{
   var job=db.queryForMap("SELECT * FROM zzal_custom_bundle_job WHERE id=? FOR UPDATE",jobId);
   if(!Objects.equals(job.get("claim_token"),token))throw new IllegalStateException("Claim token mismatch");
   if(("GENERATED".equals(job.get("state"))||"FAILED".equals(job.get("state")))&&job.get("manifest_hash")!=null){if(!hash.equals(job.get("manifest_hash")))throw new IllegalStateException("Receipt conflict");return;}
   if(!"CLAIMED".equals(job.get("state")))throw new IllegalStateException("Job not claimed");
   try{plans.complete(jobId,(String)job.get("snapshot_json"),manifest.usedSources(),failed?BundleSourcePlan.ExecutionOutcome.RESULT_PRESENT_OR_UNCERTAIN:BundleSourcePlan.ExecutionOutcome.GENERATED_COMPLETE,now);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Source receipt validation failed",e);}
   for(var a:manifest.assets()) db.update("INSERT INTO zzal_custom_bundle_asset(job_id,motion_order,asset_key,asset_hash,auto_check,human_review,published) VALUES(?,?,?,?,?,?,?)",jobId,a.motionOrder(),a.assetKey(),a.sha256(),checks.get(a.motionOrder()),a.humanReview(),a.published());
   db.update("UPDATE zzal_custom_bundle_job SET state=?,execution_id=?,manifest_hash=?,error_code=? WHERE id=?",failed?"FAILED":"GENERATED",manifest.executionId(),hash,failed?"ASSET_CHECK_FAILED":null,jobId);
   attendance.grantReady(((Number)job.get("pet_id")).longValue(),now);
  });
  if(failed)throw new IllegalArgumentException("One or more assets failed checks");
 }
}
