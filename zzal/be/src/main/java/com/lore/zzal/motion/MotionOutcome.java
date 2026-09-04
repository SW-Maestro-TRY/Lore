package com.lore.zzal.motion;

/**
 * 깨어났을 때 무엇을 배웠나. 못 배웠으면 왜 못 배웠나.
 *
 * ★ 못 배운 경우에도 <b>깨어나기는 한다.</b> 막아 두면 굽는 데 실패한 사용자가
 *   영영 못 깨우고 갇힌다 — 서버가 재시작돼 굽던 것이 사라지면 실제로 그렇게 된다.
 *
 * ★ 문구에서 아무도 탓하지 않는다. 캐릭터가 사용자를 원망하는 말은 자캐 커뮤니티에서
 *   침해로 읽히고, 캐릭터를 탓하면 내 아이가 모자란 것이 된다.
 *   그래서 <b>동작이 어려웠다</b> 쪽으로 돌린다(2026-09-03 상훈님 지시).
 */
public record MotionOutcome(boolean learned, String name, String message) {

    public static MotionOutcome learned(String name) {
        return new MotionOutcome(true, name, null);
    }

    /** 세 번 구웠는데 끝내 안 됐다. */
    public static MotionOutcome tooHard() {
        return new MotionOutcome(false, null, "너무 어려운 동작이라 배우는 데 실패했어요");
    }

    /** 아직 굽는 중. 다음에 재우면 이어서 한다. */
    public static MotionOutcome stillLearning() {
        return new MotionOutcome(false, null, "조금 더 연습이 필요한가 봐요");
    }

    /** 배울 것이 없었다(동작 목록이 비어 있음). 화면에 아무 말도 하지 않는다. */
    public static MotionOutcome nothing() {
        return new MotionOutcome(false, null, null);
    }
}
