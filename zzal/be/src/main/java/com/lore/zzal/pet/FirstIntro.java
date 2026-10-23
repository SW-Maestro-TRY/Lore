package com.lore.zzal.pet;

import java.util.List;

/** Versioned mandatory first experience. Legacy nine-step ordinals are never reinterpreted. */
public final class FirstIntro {
    private FirstIntro() { }
    public enum Step { FEED, SNACK, CLEAN, BATH, GAME, PET, NAP_SLEEP, NAP_WAKE,
        ALBUM, CHAT, PERSONALITY, DIARY_INTRO, FINISH_NOTICE, REVIEW_SEEN, COMPLETE, ATTENDANCE_INTRO }
    private static final List<Step> VERSION_2 = List.of(Step.FEED,Step.SNACK,Step.CLEAN,Step.BATH,Step.GAME,Step.PET,
        Step.NAP_SLEEP,Step.NAP_WAKE,Step.ALBUM,Step.CHAT,Step.PERSONALITY,Step.DIARY_INTRO,
        Step.FINISH_NOTICE,Step.REVIEW_SEEN,Step.COMPLETE);
    private static final List<Step> VERSION_3 = List.of(Step.FEED,Step.SNACK,Step.CLEAN,Step.BATH,Step.GAME,Step.PET,
        Step.NAP_SLEEP,Step.NAP_WAKE,Step.ALBUM,Step.DIARY_INTRO,Step.CHAT,Step.PERSONALITY,
        Step.FINISH_NOTICE,Step.REVIEW_SEEN,Step.COMPLETE);
    private static final List<Step> VERSION_4 = List.of(Step.FEED,Step.SNACK,Step.CLEAN,Step.BATH,Step.GAME,
        Step.NAP_SLEEP,Step.NAP_WAKE,Step.ATTENDANCE_INTRO,Step.FINISH_NOTICE,Step.REVIEW_SEEN,Step.COMPLETE);
    public static List<Step> sequence(int version) {
        return switch(version) { case 2 -> VERSION_2; case 3 -> VERSION_3; case 4 -> VERSION_4;
            default -> throw new IllegalArgumentException("Unsupported first intro version: " + version); };
    }
    public static boolean seenTopicAllowed(int version, Step topic) {
        return version == 4 ? topic == Step.ATTENDANCE_INTRO || topic == Step.REVIEW_SEEN
                : java.util.Set.of(Step.ALBUM,Step.PERSONALITY,Step.DIARY_INTRO,Step.REVIEW_SEEN).contains(topic);
    }
    public static int index(int version,Step step) { return sequence(version).indexOf(step); }
    @io.swagger.v3.oas.annotations.media.Schema(name="ZzalFirstIntroState",schemaResolution=io.swagger.v3.oas.annotations.media.Schema.SchemaResolution.INLINE)
    public record State(int version,int step,Step key,List<Step> completedKeys,
                        boolean active,boolean reviewPending,boolean completed,boolean napSleeping,boolean completionWillSleep) { }
    public static State of(ZzalPet pet,java.time.Instant now) {
        Integer at=pet.getFirstIntroStep();
        if(at==null)return null;
        int version=pet.getFirstIntroVersion();
        List<Step> steps=sequence(version);
        Step key=steps.get(at);
        return new State(version,at,key,steps.subList(0,at),
                at<index(version,Step.REVIEW_SEEN),key==Step.REVIEW_SEEN,
                at>=index(version,Step.REVIEW_SEEN),pet.getSleepKind()==SleepKind.NAP,
                version != 4 && pet.isInTutorial() && AwakeClock.isNight(now));
    }
}
