package com.lore.zzal.pet.receipt;
import com.lore.zzal.pet.CareAction;import io.swagger.v3.oas.annotations.media.Schema;import java.util.UUID;
/** Per-response acknowledgement; never a second action ledger. */
@Schema(name="ZzalCareReceipt",schemaResolution=Schema.SchemaResolution.INLINE)
public record CareReceipt(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) CareAction action,
 @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Status status) {
 public enum Status { APPLIED, REPLAYED, NO_OP }
}
