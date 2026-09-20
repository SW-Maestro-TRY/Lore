package com.lore.zzal.pet;

import java.util.List;

/**
 * 튜토리얼 9칸(정본 12장) — <b>시각이 아니라 순서</b>로 간다.
 *
 * <h3>★ 1.4 에서 시간이 사라졌다</h3>
 * 옛 규칙은 "부화 뒤 0·3·8·12·15·20·25·40·60분" 이었다. 그런데 부화에 2~7분이 걸려,
 * 그 사이 자리를 뜬 사람은 돌아왔을 때 <b>이미 세 칸이 지나 있고 아이는 배가 고팠다.</b>
 * 첫인상이 사람마다 달라지는 것이다. 지금은 <b>사용자가 직접 눌러야</b> 다음 칸으로 간다 —
 * 며칠 뒤에 돌아와도 튜토리얼은 처음부터 온전히 굴러간다.
 *
 * <h3>★ "어디까지 했나"는 서버가 갖는다</h3>
 * v1 은 브라우저에 두었다가 새로고침·기기 변경에서 어긋났다. {@code ZzalPet.tutorialStep} 한 칸이 정본이다.
 *
 * <h3>★ 화면이 따로 알려 주지 않는다</h3>
 * 각 칸의 행동에는 이미 제 API 가 있다(밥 = care, 채팅 = answer …). 그 API 가 들어올 때
 * <b>지금 칸의 조건과 맞으면 서버가 스스로 넘긴다.</b> 화면이 "1칸 끝났어요"를 따로 부르지 않으므로,
 * 그 호출을 빠뜨려 튜토리얼이 멈추는 일이 생길 수 없다.
 * 누를 것이 없는 마지막 칸만 {@code POST …/tutorial/done} 으로 받는다 — 그것이 시계를 켜는 순간이다.
 */
public final class TutorialSchedule {

    private TutorialSchedule() {
    }

    /** 9칸. 순서 = 정본 12장 표. */
    public enum Step {

        /** 1 배가 고픈가 봐요 — 밥 */
        FEED,
        /** 2 쓰다듬어 주세요 */
        PET,
        /** 3 뭐라고 말을 거네요 — 채팅 답(갸웃 즉시 해금) */
        CHAT,
        /** 4 이 성격이 맞나요 — 확인·수정 */
        PERSONALITY,
        /** 5 바닥을 치워 주세요 — 첫 똥은 이 칸이 만든다 */
        CLEAN,
        /** 6 같이 놀아 볼까요 — 좌우 맞히기 한 판(승패 무관) */
        GAME,
        /** 7 이 모습 가져가실래요 — 앱 밖으로 나가는 첫 결과물 */
        SHARE,
        /** 8 졸린가 봐요 — 낮잠. 재우고 곧바로 깨울 수 있다 */
        NAP,
        /** 9 이제 혼자서도 괜찮아요 — 누를 것이 없다. 여기서 시계가 켜진다 */
        DONE
    }

    public static final List<Step> STEPS = List.of(Step.values());

    public static final int TOTAL = Step.values().length;

    /** 지금 사용자가 해야 할 칸. 다 끝났으면 null. */
    public static Step currentOf(int tutorialStep) {
        return tutorialStep >= TOTAL ? null : Step.values()[tutorialStep];
    }

    public record StepState(Step key, boolean done, boolean current) {
    }

    /** 화면에 그릴 9칸. 전부 끝났으면 null — 그때부터 튜토리얼 블록 자체가 응답에서 사라진다. */
    public record State(boolean active, int step, List<StepState> steps) {
    }

    /**
     * 이 펫의 튜토리얼 상태.
     *
     * ★ 시계가 켜진 뒤에는 null 이다. "끝났는데 아직 남은 칸이 있다"는 상태가 없다 —
     *   시계를 켜는 유일한 길이 마지막 칸을 끝내는 것이기 때문이다.
     */
    public static State of(ZzalPet pet) {
        if (!pet.isInTutorial()) {
            return null;
        }
        int at = pet.getTutorialStep();
        StepState[] out = new StepState[TOTAL];
        for (int i = 0; i < TOTAL; i++) {
            out[i] = new StepState(Step.values()[i], i < at, i == at);
        }
        return new State(true, at, List.of(out));
    }
}
