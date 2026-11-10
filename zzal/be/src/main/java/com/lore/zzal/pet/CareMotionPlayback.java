package com.lore.zzal.pet;

import com.lore.zzal.attendance.OwnedCustomMotion;
import io.swagger.v3.oas.annotations.media.Schema;

/** The one selected resource for this successful care occurrence, not a grant or completion ledger. */
@Schema(name="ZzalCareMotionPlayback",schemaResolution=Schema.SchemaResolution.INLINE)
public record CareMotionPlayback(
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"FEED","SNACK","PET","CLEAN","BATH","MEDICINE"}) String situationKey,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String motionId,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"eat","pet","base"}) String basicSemantic,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"BASIC","CUSTOM"}) String sourceKind,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true,schemaResolution=Schema.SchemaResolution.INLINE) OwnedCustomMotion resource) {}
