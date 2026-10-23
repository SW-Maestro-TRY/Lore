package com.lore.zzal.attendance;
import io.swagger.v3.oas.annotations.media.Schema;
/** Owner-only delivered assets. Physical resource slot names are never action semantics. */
@Schema(name="ZzalOwnedCustomMotion",schemaResolution=Schema.SchemaResolution.INLINE)
public record OwnedCustomMotion(
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String id,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long setId,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int order,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String title,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String description,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true,description="지원 의미: base, eat, joy, sad, sick, pet, hello, sleep. 앨범 전용 자유 행동은 null. 실제 허용값은 서버와 소비자 adapter가 검증한다") String semantic,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean albumOnly,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String imageKey,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true) String anchorsKey,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String anchorPoseKey,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true) Integer baseReferenceRound,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean embeddedProps,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean suppressRoomProps,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"CUSTOM"}) String sourceKind,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"CUSTOM_MOTION_PLAN_V1","CUSTOM_MOTION_PLAN_V2"}) String planVersion,
 @io.swagger.v3.oas.annotations.media.ArraySchema(arraySchema=@Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Explicit CARE6 situation tags. V1 is empty; no semantic inference."),schema=@Schema(allowableValues={"FEED","SNACK","PET","CLEAN","BATH","MEDICINE"})) java.util.List<String> situations) {
 public OwnedCustomMotion(String id,Long setId,int order,String title,String description,String semantic,boolean albumOnly,String imageKey,String anchorsKey,String anchorPoseKey,Integer baseReferenceRound,boolean embeddedProps,boolean suppressRoomProps,String sourceKind){this(id,setId,order,title,description,semantic,albumOnly,imageKey,anchorsKey,anchorPoseKey,baseReferenceRound,embeddedProps,suppressRoomProps,sourceKind,CustomMotionPlan.VERSION,java.util.List.of());}
 public OwnedCustomMotion {
  if(planVersion==null)planVersion=CustomMotionPlan.VERSION;
  if(situations==null){if(CustomMotionPlan.CURRENT_VERSION.equals(planVersion))throw new IllegalArgumentException("V2 owned situations required");situations=java.util.List.of();}
  situations=java.util.List.copyOf(situations);
  if(!(CustomMotionPlan.VERSION.equals(planVersion)||CustomMotionPlan.CURRENT_VERSION.equals(planVersion))||!CustomMotionPlan.CARE_SITUATIONS.containsAll(situations)||new java.util.HashSet<>(situations).size()!=situations.size()||albumOnly&&!situations.isEmpty()||CustomMotionPlan.VERSION.equals(planVersion)&&!situations.isEmpty())throw new IllegalArgumentException("Invalid owned situation metadata");
 }
 public boolean careEligible(String key){return chatEligible()&&(CustomMotionPlan.VERSION.equals(planVersion)?"PET".equals(key)&&"pet".equals(semantic):situations.contains(key));}
 public boolean chatEligible(){return !albumOnly&&semantic!=null&&CustomMotionPlan.BASIC_SEMANTICS.contains(semantic);}
}
