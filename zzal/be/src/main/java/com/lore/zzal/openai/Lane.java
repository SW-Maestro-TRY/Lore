package com.lore.zzal.openai;

/**
 * OpenAI 로 나가는 줄 셋(설계 2-1). 모든 요청은 줄 하나를 골라 자리를 얻은 뒤 나간다.
 *
 * <ul>
 *   <li>{@link #IMAGE} 그림 — 부화 시트·정체성 글·격자, 맞춤 격자, 심화 행동. 자리 대기 제한 없음(작업이 기다린다)</li>
 *   <li>{@link #DIALOGUE_URGENT} 급한 대사 — 채팅, 튜토리얼 묶음. 자리 대기 2초 한 번, 넘으면 대체 대사</li>
 *   <li>{@link #DIALOGUE_BACKGROUND} 느긋한 대사 — 일기, 맞춤 기획(나중에 새벽 분류·채점 배치도 여기)</li>
 * </ul>
 * 상한은 서버 1대 기준이다. 2대가 되면 키 전체로는 상한이 두 배가 된다(설정으로 조정).
 */
public enum Lane {
    IMAGE("image"),
    DIALOGUE_URGENT("dialogue-urgent"),
    DIALOGUE_BACKGROUND("dialogue-background");

    private final String key;

    Lane(String key) {
        this.key = key;
    }

    /** 설정 키·기록에 쓰는 이름. {@code app.zzal.openai.lanes.<key>.*} */
    public String key() {
        return key;
    }
}
