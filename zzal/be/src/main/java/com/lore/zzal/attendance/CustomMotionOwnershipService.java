package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
/** Read existing ownership; never grant/publish/infer metadata from old unlock order or filenames. */
@Service
public class CustomMotionOwnershipService {
 private final JdbcTemplate db;private final ObjectMapper json=new ObjectMapper();
 public CustomMotionOwnershipService(JdbcTemplate db){this.db=db;}
 @Transactional(readOnly=true)
 public List<OwnedCustomMotion> owned(Long ownerId,Long petId){
  if(ownerId==null||petId==null)return List.of();
  var rows=db.queryForList("SELECT DISTINCT a.id AS asset_id,j.id AS set_id,a.motion_order,a.asset_key,j.snapshot_json,j.source_plan_json,j.source_plan_hash FROM zzal_attendance_reward_slot s JOIN zzal_custom_bundle_asset a ON a.id=s.asset_id AND a.asset_key=s.asset_key JOIN zzal_custom_bundle_job j ON j.id=a.job_id AND j.pet_id=s.pet_id AND j.program_version=s.program_version JOIN zzal_pet p ON p.id=s.pet_id WHERE p.user_id=? AND p.id=? AND s.state='GRANTED' AND j.state='GENERATED' AND j.manifest_hash IS NOT NULL AND a.auto_check='PASS' AND a.published=true AND a.human_review<>'REJECTED' ORDER BY j.id,a.motion_order",ownerId,petId);
  List<OwnedCustomMotion> result=new ArrayList<>();
  for(var row:rows){var motion=map(row);if(motion!=null)result.add(motion);}
  return List.copyOf(result);
 }
 public static String actionId(long assetId){if(assetId<1)throw new IllegalArgumentException("Positive owned asset ID required");return "custom:"+Long.toString(assetId,36);}
 private OwnedCustomMotion map(Map<String,Object> row){
  try{
   String frozen=(String)row.get("source_plan_json");if(frozen==null||!AttendanceService.hash(frozen).equals(row.get("source_plan_hash")))return null;
   var source=json.readTree((String)row.get("snapshot_json"));String policy=source.path("materialPolicy").asText();
   if(!BundleCommandAdapter.subjectPreservingPolicy(policy))return null;
   var root=json.readTree(frozen);if(!root.has("motionPlan"))return null;
   var plan=json.treeToValue(root.get("motionPlan"),CustomMotionPlan.class);int order=((Number)row.get("motion_order")).intValue();
   var motion=plan.motions().stream().filter(m->m.order()==order).findFirst().orElse(null);if(motion==null)return null;
   String key=(String)row.get("asset_key");if(key==null||!key.startsWith("images/")||key.contains(".."))return null;
   int slash=key.lastIndexOf('/');String filename=key.substring(slash+1);if(!filename.endsWith(".webp"))return null;
   String slot=filename.substring(0,filename.length()-5);
   List<String> slots=BundleCommandAdapter.SHEET_REFERENCE_POLICY.equals(policy)?CustomGridPrompt.SLOTS:com.lore.zzal.motion.MotionCatalog.ALL.stream().filter(m->m.layer()==com.lore.zzal.motion.MotionLayer.BASIC_1).map(com.lore.zzal.motion.MotionSpec::key).toList();
   if(order<1||order>slots.size()||!slots.get(order-1).equals(slot))return null;
   Integer baseRound=source.path("baseReferenceRound").asInt()>0?source.path("baseReferenceRound").asInt():null;
   String anchors=baseRound!=null?key.substring(0,slash)+"/anchors.json":null;
   return new OwnedCustomMotion(actionId(((Number)row.get("asset_id")).longValue()),((Number)row.get("set_id")).longValue(),order,motion.title(),motion.action(),motion.semantic(),motion.albumOnly(),key,anchors,slot,baseRound,!motion.props().isEmpty(),true,"CUSTOM",plan.version(),motion.situations());
  }catch(Exception malformed){return null;} // historical/untyped/invalid plan is not guessed into live action metadata
 }
}
