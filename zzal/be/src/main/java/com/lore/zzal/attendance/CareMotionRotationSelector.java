package com.lore.zzal.attendance;

import java.util.*;

/** Pure care-only selection. The caller supplies only ownership-service validated delivered metadata.
 * No read, write, cursor mutation, HTTP, model call or chat candidate restriction occurs here.
 * A proposed selection becomes lastSelection only after the care operation succeeds and commits.
 */
public final class CareMotionRotationSelector {
    private CareMotionRotationSelector() {}

    /** Explicit semantic supplied by the care adapter; never inferred from physical filename/order. */
    public record Situation(String key, String semantic) {
        public Situation {
            if (key == null || key.isBlank() || !CustomMotionPlan.BASIC_SEMANTICS.contains(semantic))
                throw new IllegalArgumentException("Explicit care situation and semantic required");
        }
    }

    /** Internal selection only. base means preserve that care situation's existing basic resource. */
    public record Selection(String situationKey, String id, Long setId, OwnedCustomMotion resource) {
        public boolean basic() { return setId == null; }
    }

    public static Selection next(Situation situation, Selection lastSelection,
                                 List<OwnedCustomMotion> deliveredOwned) {
        Objects.requireNonNull(situation, "situation");
        if (lastSelection != null && !situation.key().equals(lastSelection.situationKey()))
            throw new IllegalArgumentException("Cursor belongs to another care situation");
        // No previous successful occurrence: first presentation is always the original basic resource.
        if (lastSelection == null) return basic(situation);
        var bySet = new TreeMap<Long, OwnedCustomMotion>();
        if (deliveredOwned != null) for (var motion : deliveredOwned) {
            if (motion == null || !"CUSTOM".equals(motion.sourceKind()) || motion.setId() == null
                    || motion.setId() <= 0 || !motion.chatEligible()
                    || !motion.careEligible(situation.key())) continue;
            // Stable technical tie-break when a set authors several matching semantic proposals.
            bySet.merge(motion.setId(), motion, (a, b) -> a.order() < b.order() ? a
                    : a.order() > b.order() ? b : a.id().compareTo(b.id()) <= 0 ? a : b);
        }
        var entry = lastSelection.basic() ? bySet.firstEntry() : bySet.higherEntry(lastSelection.setId());
        if (entry == null) return basic(situation);
        var motion = entry.getValue();
        return new Selection(situation.key(), motion.id(), motion.setId(), motion);
    }

    private static Selection basic(Situation situation) {
        return new Selection(situation.key(), "base", null, null);
    }
}
