package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.session.Speaker;

/**
 * [지금까지] 의 한 줄 — 너: … / 상대: … (#709: 최근 3일의 판 턴 전부).
 *
 * @param speaker   누가
 * @param line      한 말
 * @param daysAgo   며칠 전 판인가(0 = 오늘, 1 = 어제, 2 = 그저께)
 * @param slot      그 판의 부름(날짜 줄에 붙는다). 모르면 null
 * @param sessionId 그 판(바뀌면 날짜 줄을 새로 단다)
 * @param current   지금 이어 가는 판인가
 */
public record HistoryLine(Speaker speaker, String line, int daysAgo, ChatSlot slot, Long sessionId, boolean current) {

    /** 이번 판의 줄(시험용 간이 생성자). */
    public HistoryLine(Speaker speaker, String line) {
        this(speaker, line, 0, null, null, true);
    }
}
