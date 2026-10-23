package com.lore.zzal.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Explicit planner/receipt boundary. Candidate lookup alone never reserves or consumes sources. */
@Service
public class BundleSourcePlan {
 public record Source(Long factId,int revision){}
 private final JdbcTemplate db;
 private final ObjectMapper json=new ObjectMapper();
 public BundleSourcePlan(JdbcTemplate db){this.db=db;}
 public static boolean requiresPlan(String snapshot)throws Exception {
  return BundleCommandAdapter.sharedPolicy(new ObjectMapper().readTree(snapshot).path("materialPolicy").asText());
 }
 /** Called by the common planner, not by candidate collection. Empty is an explicit memory-free plan. */
 @Transactional
 public void reserve(long jobId,List<Source> selected,Instant now)throws Exception {
  Objects.requireNonNull(selected,"Explicit selectedSources required");
  var job=db.queryForMap("SELECT * FROM zzal_custom_bundle_job WHERE id=? FOR UPDATE",jobId);
  String snapshot=(String)job.get("snapshot_json");
  if(!requiresPlan(snapshot))throw new IllegalStateException("Legacy job plan is immutable");
  if(!"PENDING".equals(job.get("state")))throw new IllegalStateException("Planning requires a pending job");
  boolean subjectPreserving=BundleCommandAdapter.subjectPreservingPolicy(json.readTree(snapshot).path("materialPolicy").asText());
  var candidates=json.readTree(snapshot).path("memory").path("candidates");
  Map<Long,com.fasterxml.jackson.databind.JsonNode> byId=new HashMap<>();
  candidates.forEach(c->byId.put(c.path("id").asLong(),c));
  List<Map<String,Object>> plan=new ArrayList<>();Set<Long> ids=new HashSet<>();Set<String> episodes=new HashSet<>();
  for(var source:selected){
   var fact=byId.get(source.factId());
   if(!ids.add(source.factId())||fact==null||fact.path("revision").asInt()!=source.revision()||!(subjectPreserving?Set.of("USER","CHARACTER").contains(fact.path("subject").asText()):"CHARACTER".equals(fact.path("subject").asText()))||!"ACTIVE".equals(fact.path("status").asText()))throw new IllegalArgumentException("Unknown or ineligible planned source");
   if(subjectPreserving&&!"USER_REPORTED".equals(fact.path("kind").asText()))throw new IllegalArgumentException("Unconfirmed planned source");
   String quote=fact.path("sourceQuote").asText().strip().replaceAll("\\s+"," ");
   if(!fact.path("sourceTurnId").canConvertToLong()||quote.isBlank())throw new IllegalArgumentException("Missing source provenance");
   String provenance=AttendanceService.hash(fact.path("sourceTurnId").asLong()+"|"+quote);
   if(!episodes.add(provenance))throw new IllegalArgumentException("Duplicate planned episode");
   plan.add(Map.of("factId",source.factId(),"revision",source.revision(),"provenanceKey",provenance));
  }
  plan.sort(Comparator.comparingLong(p->((Number)p.get("factId")).longValue()));
  String bytes=json.writeValueAsString(Map.of("version",1,"selectedSources",plan));
  if(job.get("source_plan_json")!=null){
   if(!bytes.equals(job.get("source_plan_json")))throw new IllegalStateException("Frozen source plan conflict");
   return;
  }
  for(var source:plan)db.update("INSERT INTO zzal_custom_bundle_source(job_id,pet_id,fact_id,fact_revision,provenance_key,state,selected_at) VALUES(?,?,?,?,?,'RESERVED',?)",jobId,job.get("pet_id"),source.get("factId"),source.get("revision"),source.get("provenanceKey"),Timestamp.from(now));
  db.update("UPDATE zzal_custom_bundle_job SET source_plan_json=?,source_plan_hash=? WHERE id=?",bytes,AttendanceService.hash(bytes),jobId);
 }
 /** New plans only: never append metadata to an already frozen historical source plan. */
 @Transactional(rollbackFor=Exception.class)
 public void reserveMotionPlan(long jobId,CustomMotionPlan motionPlan,Instant now)throws Exception {
  var row=db.queryForMap("SELECT source_plan_json FROM zzal_custom_bundle_job WHERE id=? FOR UPDATE",jobId);
  if(row.get("source_plan_json")!=null){
   var old=json.readTree((String)row.get("source_plan_json")).get("motionPlan");
   if(old==null||!old.equals(json.readTree(json.writeValueAsString(motionPlan))))throw new IllegalStateException("Frozen motion plan conflict");
   return;
  }
  String snapshot=db.queryForObject("SELECT snapshot_json FROM zzal_custom_bundle_job WHERE id=?",String.class,jobId);
  new CustomMotionPlanner().plan(snapshot,Set.of(),context->motionPlan); // recheck subject at persistence boundary
  reserve(jobId,motionPlan.selectedSources(),now);
  String plan=requirePlan(jobId,db.queryForObject("SELECT snapshot_json FROM zzal_custom_bundle_job WHERE id=?",String.class,jobId));
  var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(plan);root.set("motionPlan",json.valueToTree(motionPlan));
  String bytes=json.writeValueAsString(root);
  db.update("UPDATE zzal_custom_bundle_job SET source_plan_json=?,source_plan_hash=? WHERE id=?",bytes,AttendanceService.hash(bytes),jobId);
 }
 public Set<String> unavailableEpisodes(long petId){var excluded=new HashSet<>(db.queryForList("SELECT provenance_key FROM zzal_custom_bundle_source WHERE pet_id=? AND state IN ('RESERVED','USED')",String.class,petId));db.queryForList("SELECT fact_id FROM zzal_custom_bundle_source WHERE pet_id=? AND state IN ('RESERVED','USED')",Long.class,petId).forEach(id->excluded.add("FACT:"+id));return excluded;}
 public String requirePlan(long jobId,String snapshot)throws Exception {
  if(!requiresPlan(snapshot))return null;
  var job=db.queryForMap("SELECT source_plan_json,source_plan_hash FROM zzal_custom_bundle_job WHERE id=?",jobId);
  String plan=(String)job.get("source_plan_json");
  if(plan==null||!AttendanceService.hash(plan).equals(job.get("source_plan_hash")))throw new IllegalStateException("SOURCE_PLAN_REQUIRED");
  int released=db.queryForObject("SELECT count(*) FROM zzal_custom_bundle_source WHERE job_id=? AND state='RELEASED'",Integer.class,jobId);
  if(released!=0)throw new IllegalStateException("Source reservation released; explicit re-planning required");
  return plan;
 }
 /** Runs inside worker completion transaction after all eight asset checks. No raw memory edits. */
 public enum ExecutionOutcome { GENERATED_COMPLETE, RESULT_PRESENT_OR_UNCERTAIN }
 /** Compatibility: a failed execution is not evidence that no output exists. */
 public void complete(long jobId,String snapshot,List<Source> used,boolean successful,Instant now)throws Exception {
  complete(jobId,snapshot,used,successful?ExecutionOutcome.GENERATED_COMPLETE:ExecutionOutcome.RESULT_PRESENT_OR_UNCERTAIN,now);
 }
 public void complete(long jobId,String snapshot,List<Source> used,ExecutionOutcome outcome,Instant now)throws Exception {
  if(!requiresPlan(snapshot))return;
  requirePlan(jobId,snapshot);
  if(used==null)throw new IllegalArgumentException("Explicit usedSources receipt required");
  var rows=db.queryForList("SELECT fact_id,fact_revision FROM zzal_custom_bundle_source WHERE job_id=? AND state='RESERVED'",jobId);
  Set<Source> reserved=new HashSet<>();for(var row:rows)reserved.add(new Source(((Number)row.get("fact_id")).longValue(),((Number)row.get("fact_revision")).intValue()));
  if(new HashSet<>(used).size()!=used.size()||!reserved.containsAll(used))throw new IllegalArgumentException("Receipt source outside frozen plan");
  if(outcome==null)throw new IllegalArgumentException("Explicit execution outcome required");
  // Output exists or may exist: do not free materials for another paid job or infer consumption.
  if(outcome==ExecutionOutcome.RESULT_PRESENT_OR_UNCERTAIN)return;
  db.update("UPDATE zzal_custom_bundle_source SET state='RELEASED' WHERE job_id=? AND state='RESERVED'",jobId);
  for(var source:used)db.update("UPDATE zzal_custom_bundle_source SET state='USED',used_at=? WHERE job_id=? AND fact_id=? AND fact_revision=?",Timestamp.from(now),jobId,source.factId(),source.revision());
 }
}
