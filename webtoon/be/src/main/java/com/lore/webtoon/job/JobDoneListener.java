package com.lore.webtoon.job;

/**
 * 작업이 DONE 으로 적힌 직후에 한 번 불린다.
 *
 * {@link JobStore#done} 이 트랜잭션 안에서 부른다. 여기서 던지면 완성 기록까지 되돌아가므로,
 * 구현은 안에서 실패를 삼키고 로그만 남긴다 — 만들기는 성공한 것이고 덤으로 하는 일이다.
 * 테스트가 {@code new JobStore(...)} 로 만들 때는 아무도 안 붙어 있어 아무 일도 안 난다.
 */
public interface JobDoneListener {

    void onDone(WebtoonJob job);
}
