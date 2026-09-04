package com.lore.zzal.pet;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 아기 시간표(정본 12장) — <b>전부 서버 카운터에서 파생</b>한다. 브라우저에 저장하지 않는다.
 *
 * <h3>★ "어디까지 했나" 를 저장하지 않는 이유</h3>
 * v1 은 튜토리얼 위치를 브라우저에 두었다가 새로고침·기기 변경에서 어긋났다(플랜 T2 결정 2).
 * 여기서는 "도래" = 부화 뒤 N분, "완료" = 그 행동의 누적 카운터로 매번 계산한다. 나갔다 와도 밀린 부름이
 * 순서대로 나오고, 부름은 버튼을 잠그지 않는다(정본 0장 7).
 *
 * <h3>「해석」 9</h3>
 * 9단계가 모두 done 이면 블록 자체가 null. 60분이 지나도 남은 단계가 있으면 {@code active=false} 인 채로 남는다.
 */
public final class TutorialSchedule {

    private TutorialSchedule() {
    }

    /** 부름 하나. 순서 = 정본 12장. */
    public enum Step {
        FEED, PET, CHAT, PERSONALITY, CLEAN, GAME, SHARE, NAP, DONE
    }

    public record StepState(Step key, Instant dueAt, boolean done, boolean current) {
    }

    public record State(boolean active, long minutesSince, List<StepState> steps) {
    }

    /** 이 펫의 시간표. 전부 끝났으면 null. */
    public static State of(ZzalPet pet, Instant now) {
        Instant hatched = pet.getHatchedAt();
        if (hatched == null) {
            return null;
        }
        Step[] steps = Step.values();
        int[] minutes = ZzalRules.BABY_CALL_MINUTES;
        long minutesSince = Duration.between(hatched, now).toMinutes();

        boolean[] done = new boolean[steps.length];
        boolean allDone = true;
        for (int i = 0; i < steps.length; i++) {
            done[i] = isDone(pet, steps[i], minutesSince);
            allDone &= done[i];
        }
        if (allDone) {
            return null;
        }

        boolean currentFound = false;
        StepState[] out = new StepState[steps.length];
        for (int i = 0; i < steps.length; i++) {
            Instant dueAt = hatched.plus(Duration.ofMinutes(minutes[i]));
            boolean due = !now.isBefore(dueAt);
            boolean current = !currentFound && due && !done[i];
            if (current) {
                currentFound = true;
            }
            out[i] = new StepState(steps[i], dueAt, done[i], current);
        }
        return new State(pet.isBaby(now), minutesSince, List.of(out));
    }

    /** 완료 판정 — 전부 누적 카운터(api-v2.md 2절). */
    static boolean isDone(ZzalPet pet, Step step, long minutesSince) {
        return switch (step) {
            case FEED -> pet.getFeeds() >= 1;
            case PET -> pet.getPets() >= 1;
            case CHAT -> pet.getChatAnswers() >= 1;
            case PERSONALITY -> pet.getPersonality() != null;
            case CLEAN -> pet.getCleans() >= 1;
            case GAME -> pet.getGameStarts() >= 1;
            case SHARE -> pet.getShares() >= 1;
            case NAP -> pet.getNapCount() >= 1;
            case DONE -> minutesSince >= ZzalRules.BABY_DURATION.toMinutes();
        };
    }
}
