package com.lore.zzal.motion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 다 구운 움짤을 기계가 먼저 본다.
 *
 * <h3>지금은 아무것도 거르지 않는다</h3>
 * 실험의 게이트({@code gate.py} — 잘림·침범·빈 칸)는 실패를 가릴 수 있지만,
 * <b>"완벽" 과 "최적" 을 가르는 선은 아직 없다.</b> 그건 상훈님이 눈으로 하시던 판정이고,
 * 기하 게이트는 "물이 반대로 나감" 같은 의미 오류를 원리적으로 못 잡는다.
 *
 * 그래서 게이트가 충분히 강화될 때까지는 <b>세 판정 모두 상훈님 눈을 거치며</b>
 * 기준을 맞춰 간다(2026-09-03 지시). 여기서는 REVIEW 를 돌려 "사람이 봐야 함" 으로 두고,
 * 판정은 {@link ZzalMotion} 에 사람 판정과 나란히 쌓인다.
 *
 * <h3>버전을 남기는 이유</h3>
 * 게이트도 계속 좋아진다. 어느 버전이 내린 판정인지 모르면, 나중에 사람 판정과의
 * 일치율이 올랐을 때 게이트가 좋아진 건지 다른 게 바뀐 건지 못 가른다.
 */
@Component
public class MotionGate {

    private final String version;

    public MotionGate(@Value("${app.zzal.gate-version:g0}") String version) {
        this.version = version;
    }

    public Verdict judge(String imageKey) {
        // g0 = 아직 아무것도 안 본다. 실험 게이트를 승격하면 여기에 붙는다.
        return new Verdict(GateVerdict.REVIEW, "게이트 미적용(피팅 기간)", version);
    }

    public record Verdict(GateVerdict verdict, String note, String version) {
    }
}
