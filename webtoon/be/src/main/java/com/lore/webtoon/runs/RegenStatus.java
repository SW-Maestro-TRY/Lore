package com.lore.webtoon.runs;

/** {@link PageRegen} 이 지금 어느 걸음인가. 화면이 읽는 글자를 그대로 담는다. */
enum RegenStatus {

    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    ERROR("error");

    private final String wire;

    RegenStatus(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
