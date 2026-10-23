package com.lore.zzal.pet.receipt;
import com.lore.zzal.pet.PetService;
/** Internal result keeps existing Action-returning callers compatible. */
public record CareDeliveryResult(PetService.Action action,CareReceipt careReceipt) {}
