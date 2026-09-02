package com.lore.zzal.generation;

/**
 * 생성 파이프라인의 한 단계.
 *
 * ★ 각 단계는 **자기 일만 알고 앞뒤를 모른다.** 필요한 것은 이름으로 꺼내 쓰고,
 *   만든 것은 이름을 붙여 돌려준다. 그래서 단계를 빼거나 순서를 바꿔도
 *   다른 단계의 코드는 바뀌지 않는다.
 *
 * ★ 단계를 없앨 때도 클래스는 지우지 않는다 — 그 버전으로 만들어진 옛 펫들을
 *   설명할 수 있어야 하고, 되돌릴 수도 있어야 한다.
 */
public interface GenerationStep {

    /** 기록에 남는 이름. "sheet" · "identity" · "grid" · "postprocess" */
    String name();

    /** 이 단계에 허용된 시간(초). 넘으면 시간 초과로 끊는다. */
    int limitSeconds();

    /** 화면에 보여줄 말. 남은 시간이 아니라 지금 하는 일을 알린다. */
    String label();

    StepResult run(StepContext ctx) throws Exception;
}
