package com.lore.zzal.motion;

/** Attendance custom bundles replace new layer-two grids and gifts. Existing assets stay readable. */
public final class RetiredRewardGeneration {
    private RetiredRewardGeneration() {}

    public static boolean layerTwoAutoEnabled() { return false; }

    public static boolean isRetiredNewMotion(int seq) { return (seq >= 9 && seq <= 16) || isRetiredGift(seq); }

    public static boolean isRetiredGift(int seq) { return seq == 101 || seq == 102; }
}
