package com.lore.zzal.archive;

import com.lore.zzal.pet.ZzalRules;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 보관할 행동 기록 한 줄 — {@code zzal_event} 의 칸을 그대로 옮긴 것.
 *
 * <h3>★ 왜 엔티티({@code AnalyticsEvent})를 안 쓰나</h3>
 * 그 엔티티는 공용({@code common})이고 {@code props · path · referrer · device} 에
 * 읽는 문을 안 열어 뒀다 — 지금은 "쌓는 것" 만이 목적이라는 판단이 주석에 적혀 있다.
 * 보관은 <b>모든 칸</b>을 그대로 떠야 하므로 공용 코드를 고치는 대신 표를 직접 읽는다.
 * 덤으로 이 길에는 {@code UPDATE · DELETE} 를 쓸 자리가 아예 없다 — 원본은 읽기만 한다.
 */
public record EventRow(
        long id,
        String name,
        String anonId,
        Long userId,
        String props,
        String path,
        String referrer,
        String source,
        String device,
        String variant,
        Instant occurredAt,
        Instant receivedAt) {

    /**
     * 이 줄이 들어갈 날짜 칸({@code dt=}).
     *
     * <h3>★ 브라우저 시계({@code occurred_at})가 아니라 서버 도착 시각({@code received_at})으로 가른다</h3>
     * {@code occurred_at} 은 사용자 기기의 시계다 — 시간대를 손으로 바꿔 둔 기기가 실제로 있어서
     * ({@code AnalyticsService} 주석) 그 값으로 칸을 가르면 <b>한 줄이 엉뚱한 날 폴더</b>로 간다.
     * 게다가 도착 시각은 id 순서와 거의 같이 흐르므로, 같은 날 칸이 파일 안에서 이어진 구간이 된다.
     * 사람의 "언제 했나" 는 파일 안의 {@code occurred_at} 에 그대로 남아 있으니 잃는 것은 없다.
     */
    public LocalDate partitionDate() {
        return receivedAt.atZone(ZzalRules.ZONE).toLocalDate();
    }
}
