package com.lore.zzal.attendance;

import com.lore.zzal.pet.ZzalPet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Caller holds the pet row lock; HTTP visits only persist an outbox, never invoke a paid generator. */
@Service
public class AttendanceService {
 @org.springframework.beans.factory.annotation.Autowired
 private com.lore.zzal.generation.client.PersistentProviderUsageRecorder metrics;

 public static final String PROGRAM="first-v1";
 private static final int[] DAYS={3,4,5,6,7,8,9,10};
 private final JdbcTemplate db;
 private final PublishedBackgroundCatalog backgrounds;
 private final RewardMaterialProvider memories;
 private final CustomMotionOwnershipService customMotions;
 private final NextCustomSetQueueService nextSets;
 private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
 public AttendanceService(JdbcTemplate db,PublishedBackgroundCatalog backgrounds,RewardMaterialProvider memories){this(db,backgrounds,memories,null);}
 public AttendanceService(JdbcTemplate db,PublishedBackgroundCatalog backgrounds,RewardMaterialProvider memories,CustomMotionOwnershipService customMotions){this(db,backgrounds,memories,customMotions,null);}
 @org.springframework.beans.factory.annotation.Autowired
 public AttendanceService(JdbcTemplate db,PublishedBackgroundCatalog backgrounds,RewardMaterialProvider memories,CustomMotionOwnershipService customMotions,NextCustomSetQueueService nextSets){this.db=db;this.backgrounds=backgrounds;this.memories=memories;this.customMotions=customMotions;this.nextSets=nextSets;}
 private record InitialBackground(String key,String cluster){}
 private InitialBackground initialBackground(Long petId){
  return db.query("SELECT asset_key,source_cluster_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND program_version=? AND slot_key='legacy-background' AND state='GRANTED'",(r,n)->new InitialBackground(r.getString(1),r.getString(2)),petId,PROGRAM).stream().findFirst().orElse(null);
 }
 /** Called under the pet lock by character submission and visits; existing ownership is immutable. */
 @Transactional
 public void initializeBackground(ZzalPet pet,Instant now){
  if(initialBackground(pet.getId())!=null)return;
  String second=db.query("SELECT asset_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND program_version=? AND slot_key='background' AND state='GRANTED'",(r,n)->r.getString(1),pet.getId(),PROGRAM).stream().findFirst().orElse(null);
  boolean room=pet.getBackground()==null||"room".equals(pet.getBackground());
  // A selected pre-program background is already the initial entitlement, not a new reward.
  if(!room&&!Objects.equals(pet.getBackground(),second)){
   db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key,granted_at,source_cluster_key) VALUES(?,?,'legacy-background',0,'GRANTED',?,?,?) ON CONFLICT DO NOTHING",pet.getId(),PROGRAM,pet.getBackground(),Timestamp.from(now),backgrounds.clusterOf(pet.getBackground()).orElse(null));
   return;
  }
  // Historical D2 ownership must survive; recover its missing companion, never duplicate it.
  Optional<PublishedBackgroundCatalog.Entry> candidate=second==null?backgrounds.select(pet)
   :backgrounds.clusterOf(second).flatMap(cluster->backgrounds.select(pet,second,cluster));
  candidate.filter(e->PublishedBackgroundCatalog.clusterKey(e)!=null).ifPresent(e->{
   int inserted=db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key,granted_at,source_cluster_key) VALUES(?,?,'legacy-background',0,'GRANTED',?,?,?) ON CONFLICT DO NOTHING",pet.getId(),PROGRAM,e.key(),Timestamp.from(now),PublishedBackgroundCatalog.clusterKey(e));
   if(inserted==1&&room&&second==null)pet.changeBackground(e.key());
  });
 }
 private Optional<PublishedBackgroundCatalog.Entry> rewardBackground(ZzalPet pet){
  var initial=initialBackground(pet.getId());
  if(initial==null)return backgrounds.select(pet); // historical users without an initial ownership record
  String cluster=initial.cluster();
  if(cluster==null){
   cluster=backgrounds.clusterOf(initial.key()).orElse(null);
   if(cluster!=null)db.update("UPDATE zzal_attendance_reward_slot SET source_cluster_key=? WHERE pet_id=? AND program_version=? AND slot_key='legacy-background' AND source_cluster_key IS NULL",cluster,pet.getId(),PROGRAM);
  }
  // A disappeared/unknown cluster is pending, never a cross-cluster fallback.
  if(cluster==null)return Optional.empty();
  return backgrounds.select(pet,initial.key(),cluster);
 }
 @Transactional
 public void observe(ZzalPet pet,Instant now,boolean tutorialCompleted){
  if(!pet.isAlive() || pet.isTraveling()) return;
  initializeBackground(pet,now);
  if(pet.isInTutorial()){
   if(pet.hasFirstIntro() && pet.introAt(com.lore.zzal.pet.FirstIntro.Step.FINISH_NOTICE)) prepareBackgroundPreview(pet);
   attach(pet);return;
  }
  int day=count(pet.getId());
  if(!tutorialCompleted && pet.isSleeping()) { attach(pet); return; }
  String cycle=pet.getAttendanceNightCycleAt()==null?"INITIAL":"NIGHT:"+pet.getAttendanceNightCycleAt();
  if(day==0 || !tutorialCompleted) {
   db.update("INSERT INTO zzal_awake_visit(pet_id,program_version,cycle_key,event_id,observed_at) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
    pet.getId(),PROGRAM,cycle,PROGRAM+":"+pet.getId()+":"+cycle,Timestamp.from(now));
  }
  day=count(pet.getId());
  if(day>=2){
   if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM zzal_custom_bundle_job WHERE pet_id=? AND program_version=? AND bundle_version='bundle-1')",Boolean.class,pet.getId(),PROGRAM))){
   String snapshot=snapshot(pet,now);
   db.update("INSERT INTO zzal_custom_bundle_job(pet_id,program_version,bundle_version,snapshot_json,input_hash,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",pet.getId(),PROGRAM,"bundle-1",snapshot,hash(snapshot),Timestamp.from(now));
   }
   eligible(pet.getId(),"background",2);
  }
  for(int i=0;i<DAYS.length;i++) if(day>=DAYS[i]) eligible(pet.getId(),"motion-"+(i+1),DAYS[i]);
  if(day>=2){
   var initial=initialBackground(pet.getId());
   if(initial!=null){
    var preview=db.query("SELECT asset_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND program_version=? AND slot_key='background' AND state='ELIGIBLE' AND asset_key IS NOT NULL",(r,n)->r.getString(1),pet.getId(),PROGRAM).stream().findFirst();
    String cluster=initial.cluster()==null?backgrounds.clusterOf(initial.key()).orElse(null):initial.cluster();
    preview.filter(key->key.equals(initial.key())||cluster==null||!backgrounds.clusterOf(key).filter(cluster::equals).isPresent())
     .ifPresent(key->db.update("UPDATE zzal_attendance_reward_slot SET asset_key=NULL WHERE pet_id=? AND program_version=? AND slot_key='background' AND state='ELIGIBLE' AND asset_key=?",pet.getId(),PROGRAM,key));
   }
   rewardBackground(pet).ifPresent(bg->db.update("UPDATE zzal_attendance_reward_slot SET asset_key=? WHERE pet_id=? AND program_version=? AND slot_key='background' AND state='ELIGIBLE' AND asset_key IS NULL",bg.key(),pet.getId(),PROGRAM));
   db.update("UPDATE zzal_attendance_reward_slot SET state='GRANTED',granted_at=? WHERE pet_id=? AND program_version=? AND slot_key='background' AND state='ELIGIBLE' AND asset_key IS NOT NULL",Timestamp.from(now),pet.getId(),PROGRAM);
  }
  grantReady(pet.getId(),now);
  // Eligibility/outbox only. Never execute a paid planner or image provider from visits.
  if(nextSets!=null && !pet.isSleeping() && !pet.isTraveling())
   nextSets.enqueue(pet.getUserId(),pet.getId(),now,()->snapshot(pet,now));
  attach(pet);
 }
 private void eligible(Long id,String slot,int day){db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day) VALUES(?,?,?,?) ON CONFLICT(pet_id,program_version,slot_key) DO UPDATE SET eligible_day=LEAST(zzal_attendance_reward_slot.eligible_day,EXCLUDED.eligible_day) WHERE zzal_attendance_reward_slot.state='ELIGIBLE'",id,PROGRAM,slot,day);}
 private void prepareBackgroundPreview(ZzalPet pet){
  rewardBackground(pet).ifPresent(bg->db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key) VALUES(?,?,'background',2,'ELIGIBLE',?) ON CONFLICT(pet_id,program_version,slot_key) DO NOTHING",pet.getId(),PROGRAM,bg.key()));
 }
 private int count(Long id){return db.queryForObject("SELECT count(*) FROM zzal_awake_visit WHERE pet_id=? AND program_version=?",Integer.class,id,PROGRAM);}
 /** Only published, checked, non-rejected assets become ownership. Repeated delivery preserves the same row. */
 @Transactional
 public void grantReady(Long id,Instant now){
  var granted=db.query("UPDATE zzal_attendance_reward_slot s SET state='GRANTED',asset_id=a.id,asset_key=a.asset_key,granted_at=? FROM zzal_custom_bundle_asset a JOIN zzal_custom_bundle_job j ON j.id=a.job_id WHERE s.pet_id=? AND s.program_version=? AND j.pet_id=s.pet_id AND j.program_version=s.program_version AND j.bundle_version='bundle-1' AND j.state IN ('GENERATED','FAILED') AND (j.state='GENERATED' OR j.source_plan_json IS NULL OR NOT jsonb_exists(j.source_plan_json::jsonb,'motionPlan')) AND j.manifest_hash IS NOT NULL AND s.slot_key='motion-'||a.motion_order AND s.state='ELIGIBLE' AND a.auto_check='PASS' AND a.human_review<>'REJECTED' AND a.published=true RETURNING s.slot_key",(r,n)->r.getString(1),Timestamp.from(now),id,PROGRAM);
  if(metrics!=null) for(String slot:granted) metrics.outcome("attendance_grant",id+":"+PROGRAM+":"+slot,null,id,null,"granted");
  if(nextSets!=null){
   Long owner=db.queryForObject("SELECT user_id FROM zzal_pet WHERE id=?",Long.class,id);
   nextSets.grantReadyQueued(owner,id,now);
  }
 }
 public boolean ownsBackground(Long petId,String key){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM zzal_attendance_reward_slot WHERE pet_id=? AND program_version=? AND slot_key IN ('background','legacy-background') AND state='GRANTED' AND asset_key=?)",Boolean.class,petId,PROGRAM,key));}
 public void attach(ZzalPet pet){pet.setAttendanceRewards(view(pet));}
 public AttendanceRewards view(ZzalPet pet){
  var states=db.queryForList("SELECT state FROM zzal_custom_bundle_job WHERE pet_id=? AND program_version=? AND bundle_version='bundle-1'",String.class,pet.getId(),PROGRAM);
  var owned=db.query("SELECT slot_key,asset_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND program_version=? AND state='GRANTED' ORDER BY eligible_day",(r,n)->{String slot=r.getString(1),key=r.getString(2);return new AttendanceRewards.OwnedReward(slot,slot.endsWith("background")?"BACKGROUND":"MOTION",key,slot.equals("legacy-background") && !key.startsWith("images/")?"/images/zzal/bg/"+key+".webp":backgrounds.url(key),rewardMotionOrder(slot));},pet.getId(),PROGRAM);
  int attendanceDay=count(pet.getId());
  int pending=db.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE pet_id=? AND program_version=? AND state='ELIGIBLE' AND eligible_day<=?",Integer.class,pet.getId(),PROGRAM,attendanceDay);
  var preview=db.query("SELECT asset_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND program_version=? AND slot_key='background' AND state='ELIGIBLE' AND asset_key IS NOT NULL",(r,n)->new AttendanceRewards.Preview(r.getString(1),backgrounds.url(r.getString(1))),pet.getId(),PROGRAM).stream().findFirst().orElse(null);
  return new AttendanceRewards(PROGRAM,attendanceDay,states.isEmpty()?"NONE":states.getFirst(),pending,owned,new AttendanceRewards.Background(pet.getBackground(),owned.stream().filter(x->x.kind().equals("BACKGROUND")).map(x->new AttendanceRewards.OwnedBackground(x.assetKey(),x.url())).toList(),preview),customMotions==null?List.of():customMotions.owned(pet.getUserId(),pet.getId()),newRewardCount(pet),unreadDiaryCount(pet.getId()));
 }
 /** Gifts granted after the owner last opened the gifts screen. The initial legacy background is an entitlement, not a gift. */
 int newRewardCount(ZzalPet pet){
  Instant seen=pet.getRewardsSeenAt();
  String base="SELECT count(*) FROM zzal_attendance_reward_slot WHERE pet_id=? AND program_version=? AND state='GRANTED' AND slot_key<>'legacy-background' AND granted_at IS NOT NULL";
  return seen==null?db.queryForObject(base,Integer.class,pet.getId(),PROGRAM)
   :db.queryForObject(base+" AND granted_at>?",Integer.class,pet.getId(),PROGRAM,Timestamp.from(seen));
 }
 /** Same rule as the diary list's unreadCount (READY and unread). */
 private int unreadDiaryCount(Long petId){
  return db.queryForObject("SELECT count(*) FROM zzal_diary_entry WHERE pet_id=? AND status='READY' AND read_at IS NULL",Integer.class,petId);
 }
 /**
  * The owner opened the gifts screen. Never moves backwards, and never before the newest grant it could have shown:
  * a worker may stamp granted_at with the real clock while the pet runs on a dev-offset clock.
  */
 public void markRewardsSeen(ZzalPet pet,Instant now){
  Timestamp latest=db.queryForObject("SELECT max(granted_at) FROM zzal_attendance_reward_slot WHERE pet_id=? AND program_version=? AND state='GRANTED'",Timestamp.class,pet.getId(),PROGRAM);
  Instant newest=latest==null?null:latest.toInstant();
  pet.markRewardsSeen(newest!=null&&newest.isAfter(now)?newest:now);
  attach(pet);
 }
 static Integer rewardMotionOrder(String slot){
  if(slot==null)return null;
  var matcher=java.util.regex.Pattern.compile("(?:bundle-[1-9][0-9]*:)?motion-([1-8])").matcher(slot);
  return matcher.matches()?Integer.valueOf(matcher.group(1)):null;
 }
 private String snapshot(ZzalPet pet,Instant now){
  Map<String,Object> source=new LinkedHashMap<>();
  source.put("schemaVersion",4);
  source.put("materialPolicy",BundleCommandAdapter.SHEET_REFERENCE_POLICY);
  source.put("name",pet.getName());
  Map<String,Object> legacy=new LinkedHashMap<>();legacy.put("note",pet.getNote());legacy.put("world",pet.getWorld());legacy.put("personalities",pet.getPersonalities());legacy.put("tone",pet.getTone());legacy.put("genre",pet.getGenre());
  source.put("legacySource",legacy);source.put("persona",pet.getAuthoredPersona());source.put("personaSourceKind",pet.getAuthoredPersona()==null?"LEGACY":"AUTHORED");source.put("personaSourceHash",com.lore.zzal.pet.persona.PersonaSourceVersion.of(pet));
  source.put("identity",pet.getIdentityText());source.put("sourceImageKey",pet.getSourceImageKey());source.put("identityHash",hash(String.valueOf(pet.getIdentityText())+"|"+pet.getSourceImageKey()));
  source.put("customGridTemplateVersion",CustomGridPrompt.TEMPLATE_VERSION);source.put("customGridTemplateHash",CustomGridPrompt.templateHash());
  source.put("sheetImageKey",pet.getSheetImageKey());source.put("baseReferenceRound",pet.getBasicRound());
  source.put("appearancePipelineVersion",pet.getHatchPipelineVersion());
  source.put("anchorsKey",com.lore.zzal.motion.MotionImageKeys.hasAnchors(pet.getHatchPipelineVersion(),pet.getBasicRound())?com.lore.zzal.motion.MotionImageKeys.anchors(pet.getId(),pet.getBasicRound()):null);
  // Same material contract for every custom set; existing snapshots are never regenerated.
  source.put("memory",memories.memory(pet,(String)source.get("identityHash"),now));
  try{return json.writeValueAsString(source);}catch(Exception e){throw new IllegalStateException("Cannot snapshot reward source",e);}
 }
 static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
