package com.lore.webtoon.job;

/**
 * 만들기가 지금 어떤 상태인가.
 *
 * 이름을 그대로 DB 에 적는다(순서 번호가 아니라). 사이에 하나를 끼워 넣는
 * 순간 옛 줄의 뜻이 통째로 바뀐다.
 *
 * 화면(프로토타입에서 옮겨 온 것)이 읽는 값과 **같은 글자**로 내보내야 한다 —
 * {@link #wire()} 참고.
 */
public enum JobStatus {

    /** 차례를 기다린다. 한 번에 하나씩만 돈다. */
    QUEUED("queued"),

    /** 파이썬이 돌고 있다. */
    RUNNING("running"),

    /** 캐릭터 시트를 사람이 볼 차례. */
    AWAITING_SHEET("awaiting_sheet"),

    /** 이야기 넷 중 하나를 사람이 고를 차례. */
    AWAITING_PICK("awaiting_pick"),

    DONE("done"),
    ERROR("error");

    private final String wire;

    JobStatus(String wire) {
        this.wire = wire;
    }

    /** 화면에 나가는 글자. 파이썬 서버가 쓰던 것과 같아야 한다. */
    public String wire() {
        return wire;
    }

    public boolean isAwaiting() {
        return this == AWAITING_SHEET || this == AWAITING_PICK;
    }

    public boolean isOver() {
        return this == DONE || this == ERROR;
    }
}
