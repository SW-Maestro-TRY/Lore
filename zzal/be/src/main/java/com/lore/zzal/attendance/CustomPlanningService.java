package com.lore.zzal.attendance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
/** Explicit planning entry; no scheduler/model bean is auto-started or auto-retried. */
@Service
public class CustomPlanningService {
 private final JdbcTemplate db;private final BundleSourcePlan reservations;
 public CustomPlanningService(JdbcTemplate db,BundleSourcePlan reservations){this.db=db;this.reservations=reservations;}
 public CustomMotionPlan prepare(long jobId,CustomMotionPlanner.Author author,Instant now)throws Exception {
  var row=db.queryForMap("SELECT pet_id,snapshot_json,source_plan_json,state FROM zzal_custom_bundle_job WHERE id=?",jobId);
  if(row.get("source_plan_json")!=null){var frozen=new ObjectMapper().readTree((String)row.get("source_plan_json")).get("motionPlan");if(frozen==null)throw new IllegalStateException("Historical source plan cannot be rewritten");return new ObjectMapper().treeToValue(frozen,CustomMotionPlan.class);}
  if(!"PENDING".equals(row.get("state")))throw new IllegalStateException("Planning requires pending job");
  // Commit a durable claim before invoking an author that may later be a paid model.
  // A timeout/crash is never silently retried by another concurrent prepare.
  var claim=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(db.getDataSource())));
  claim.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  boolean owned=Boolean.TRUE.equals(claim.execute(status->db.update("UPDATE zzal_custom_bundle_job SET error_code='PLANNING_IN_PROGRESS' WHERE id=? AND state='PENDING' AND source_plan_json IS NULL AND error_code IS NULL",jobId)==1));
  if(!owned)throw new IllegalStateException("PLANNING_ALREADY_CLAIMED");
  try{
  var plan=new CustomMotionPlanner().plan((String)row.get("snapshot_json"),reservations.unavailableEpisodes(((Number)row.get("pet_id")).longValue()),author);
  // Race with another job is rejected by the reservation unique constraint, never silently replanned.
  reservations.reserveMotionPlan(jobId,plan,now);
  db.update("UPDATE zzal_custom_bundle_job SET error_code=NULL WHERE id=? AND error_code='PLANNING_IN_PROGRESS'",jobId);return plan;
  }catch(Exception e){throw e;} // durable claim remains; explicit resolution required, no automatic author replay
 }
}
