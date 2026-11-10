package com.lore.zzal.pet;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionLayer;

/** Read-only cutover preview, never called to mint new ownership during normal actions. */
public final class LegacyLayerTwoOwnership {
    private LegacyLayerTwoOwnership() {}

    public static int previewOldComputedOwnership(ZzalPet pet, MotionCatalog catalog) {
        if (!pet.isLayer2Ready()) return 0;
        int mask = 0;
        for (var spec : catalog.basic()) {
            if (spec.layer() == MotionLayer.BASIC_2 && spec.seq() >= 9 && spec.seq() <= 16
                    && UnlockRules.current(pet, spec.unlockRule().kind(), catalog) >= spec.unlockRule().target()) {
                mask |= 1 << (spec.seq() - 9);
            }
        }
        return mask;
    }

    public static int previewPreservedOwnership(ZzalPet pet, MotionCatalog catalog,
                                                java.util.Collection<com.lore.zzal.motion.ZzalMotion> rows) {
        int mask = previewOldComputedOwnership(pet, catalog);
        for (var row : rows) {
            if (row.getSeq() >= 9 && row.getSeq() <= 16 && row.getUnlockedAt() != null) {
                mask |= 1 << (row.getSeq() - 9);
            }
        }
        return mask;
    }
}
