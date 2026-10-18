package com.lore.zzal.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Explicit local queue operation only: no scheduler, endpoint, generator, CLI or automatic paid retry.
 * Snapshot supplier must reuse the current common persona/appearance/available-memory snapshot contract.
 * Existing first-set AttendanceService and all historical snapshots/receipts remain unchanged.
 */
@Service
public class NextCustomSetQueueService {
    private final JdbcTemplate db;
    private final ObjectMapper json=new ObjectMapper();
    public NextCustomSetQueueService(JdbcTemplate db){this.db=db;}
    public record Evaluation(Long previousJobId,int previousSet,NextCustomSetEligibility.Decision decision){}
    public record Queued(Evaluation evaluation,Long jobId,boolean created){}
    @Transactional(readOnly=true)
    public Evaluation evaluate(Long ownerId,Long petId,Instant now){
        requireOwner(ownerId,petId,false);
        return inspect(petId,now);
    }
    @Transactional
    public Queued enqueue(Long ownerId,Long petId,Instant now,Supplier<String> frozenSnapshot){
        requireOwner(ownerId,petId,true);
        var evaluation=inspect(petId,now);
        if(evaluation.previousJobId()==null||!evaluation.decision().eligible())return new Queued(evaluation,null,false);
        int next=Math.addExact(evaluation.previousSet(),1);
        String version="bundle-"+next;
        String snapshot=Objects.requireNonNull(frozenSnapshot.get(),"Frozen snapshot required");
        validateCommonSnapshot(snapshot);
        Long id=db.query("INSERT INTO zzal_custom_bundle_job(pet_id,program_version,bundle_version,snapshot_json,input_hash,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(pet_id,program_version,bundle_version) DO NOTHING RETURNING id",(r,n)->r.getLong(1),petId,AttendanceService.PROGRAM,version,snapshot,AttendanceService.hash(snapshot),Timestamp.from(now)).stream().findFirst().orElse(null);
        if(id==null)return new Queued(evaluation,null,false);
        // Same relative timing as first set: queue on day 2, first reward on the following service cycle.
        int generationDay=db.queryForObject("SELECT count(*) FROM zzal_awake_visit WHERE pet_id=? AND program_version=? AND observed_at<=?",Integer.class,petId,AttendanceService.PROGRAM,Timestamp.from(now));
        for(int order=1;order<=8;order++)db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",petId,AttendanceService.PROGRAM,version+":motion-"+order,generationDay+order);
        return new Queued(evaluation,id,true);
    }
    /** Caller holds the pet ownership lock. Late assets deliver accrued rights, never collapse attendance. */
    @Transactional
    public int grantReadyQueued(Long ownerId,Long petId,Instant now){
        requireOwner(ownerId,petId,true);
        return db.update("UPDATE zzal_attendance_reward_slot s SET state='GRANTED',asset_id=a.id,asset_key=a.asset_key,granted_at=? FROM zzal_custom_bundle_asset a JOIN zzal_custom_bundle_job j ON j.id=a.job_id WHERE s.pet_id=? AND s.program_version=? AND j.pet_id=s.pet_id AND j.program_version=s.program_version AND j.bundle_version ~ '^bundle-[2-9][0-9]*$|^bundle-1[0-9]+$' AND s.slot_key=j.bundle_version||':motion-'||a.motion_order AND s.eligible_day<=(SELECT count(*) FROM zzal_awake_visit v WHERE v.pet_id=s.pet_id AND v.program_version=s.program_version AND v.observed_at<=?) AND s.state='ELIGIBLE' AND j.state='GENERATED' AND j.manifest_hash IS NOT NULL AND a.auto_check='PASS' AND a.published=true AND a.human_review<>'REJECTED'",Timestamp.from(now),petId,AttendanceService.PROGRAM,Timestamp.from(now));
    }
    private void requireOwner(Long ownerId,Long petId,boolean lock){
        var owners=db.queryForList("SELECT user_id FROM zzal_pet WHERE id=?"+(lock?" FOR UPDATE":""),Long.class,petId);
        if(owners.size()!=1||!Objects.equals(owners.getFirst(),ownerId))throw new IllegalArgumentException("Owned pet required");
    }
    private Evaluation inspect(Long petId,Instant now){
        var jobs=db.queryForList("SELECT id,bundle_version FROM zzal_custom_bundle_job WHERE pet_id=? AND program_version=?",petId,AttendanceService.PROGRAM);
        Long latest=null;int set=0;
        for(var job:jobs){String version=(String)job.get("bundle_version");if(!version.matches("bundle-[1-9][0-9]*"))throw new IllegalStateException("Unrecognized set history");int number=Integer.parseInt(version.substring(7));if(number>set){set=number;latest=((Number)job.get("id")).longValue();}}
        if(latest==null)return new Evaluation(null,0,new NextCustomSetEligibility.Decision(NextCustomSetEligibility.State.AWAITING_ALL_EIGHT,0,null,null,null));
        Map<Integer,Instant> received=new HashMap<>();
        var rows=db.queryForList("SELECT a.motion_order,s.granted_at FROM zzal_attendance_reward_slot s JOIN zzal_custom_bundle_asset a ON a.id=s.asset_id AND a.asset_key=s.asset_key WHERE s.pet_id=? AND s.program_version=? AND a.job_id=? AND s.state='GRANTED' AND s.granted_at<=?",petId,AttendanceService.PROGRAM,latest,Timestamp.from(now));
        for(var row:rows){int order=((Number)row.get("motion_order")).intValue();Instant at=((Timestamp)row.get("granted_at")).toInstant();received.merge(order,at,(a,b)->a.isBefore(b)?a:b);}
        var cycles=db.query("WITH cycles AS (SELECT id,cycle_key,observed_at,lead(observed_at) OVER(ORDER BY observed_at,id) AS next_at FROM zzal_awake_visit WHERE pet_id=? AND program_version=? AND observed_at<=?) SELECT c.cycle_key,c.observed_at,count(t.id) AS user_messages FROM cycles c LEFT JOIN zzal_chat_turn t ON t.pet_id=? AND t.speaker='USER' AND t.created_at>=c.observed_at AND (c.next_at IS NULL OR t.created_at<c.next_at) AND t.created_at<=? GROUP BY c.id,c.cycle_key,c.observed_at ORDER BY c.observed_at,c.id",(r,n)->new NextCustomSetEligibility.Cycle(r.getString("cycle_key"),r.getTimestamp("observed_at").toInstant(),r.getInt("user_messages")),petId,AttendanceService.PROGRAM,Timestamp.from(now),petId,Timestamp.from(now));
        return new Evaluation(latest,set,NextCustomSetEligibility.evaluate(received,cycles));
    }
    private void validateCommonSnapshot(String snapshot){
        try{var root=json.readTree(snapshot);if(!BundleCommandAdapter.SHEET_REFERENCE_POLICY.equals(root.path("materialPolicy").asText())||root.path("sheetImageKey").asText().isBlank()||root.path("identity").asText().isBlank()||!root.path("memory").isObject()||root.path("customGridTemplateVersion").asText().isBlank()||root.path("customGridTemplateHash").asText().isBlank())throw new IllegalArgumentException("Current common frozen snapshot required");}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("Invalid frozen snapshot",e);}
    }
}
