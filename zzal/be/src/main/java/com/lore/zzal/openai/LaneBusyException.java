package com.lore.zzal.openai;

/**
 * 자리 대기 상한(급한 대사 1초)을 넘었다 — OpenAI 로는 보내지 않았다. 돈은 나가지 않았다.
 * 대사 쪽은 이 실패를 다른 실패와 같이 받아 대체 대사로 간다.
 */
public class LaneBusyException extends Exception {
    private final Lane lane;

    public LaneBusyException(Lane lane, long waitedMs) {
        super("OpenAI 줄 자리 없음 — %s 줄에서 %dms 기다렸다".formatted(lane.key(), waitedMs));
        this.lane = lane;
    }

    public Lane lane() {
        return lane;
    }
}
