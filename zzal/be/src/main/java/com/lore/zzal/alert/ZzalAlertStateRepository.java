package com.lore.zzal.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * 경보 중복 막기 — <b>한 문장으로 판정하고 기록한다</b>.
 *
 * <h3>★★ 왜 읽고 나서 쓰지 않나</h3>
 * "마지막 값을 읽는다 → 다르면 메일을 보낸다 → 값을 쓴다" 로 짜면, 두 스레드가 <b>같은 값을 읽고
 * 둘 다 보낸다</b>(부화는 3스레드가 나란히 돈다). 게다가 그 사고는 평소에 안 나고 몰릴 때만 나서
 * 시험으로도 잘 안 잡힌다. 그래서 판정을 DB 의 UPSERT 한 문장에 맡긴다 — 실제로 값을 바꾼 쪽만
 * 1 을 돌려받고, 그 쪽만 메일을 보낸다.
 */
public interface ZzalAlertStateRepository extends JpaRepository<ZzalAlertState, String> {

    /**
     * 값이 <b>달라졌을 때만</b> 갱신한다. 바꿨으면 1, 이미 같은 값이면 0.
     *
     * ★ 날짜·구간 id 처럼 "같으면 같은 사건" 인 종류가 이걸 쓴다 — 하루 상한(그날 한 번) ·
     *   밤 굽기 실패(그 밤 한 번) · 부화 연속 실패(그 구간 한 번).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into zzal_alert_state (alert_key, last_value, sent_at)
                 values (:key, :value, :now)
            on conflict (alert_key) do update
                    set last_value = excluded.last_value, sent_at = excluded.sent_at
                  where zzal_alert_state.last_value is distinct from excluded.last_value
            """, nativeQuery = true)
    int claimOnce(@Param("key") String key, @Param("value") String value, @Param("now") Instant now);

    /**
     * <b>더 큰 금액일 때만</b> 갱신한다. 올렸으면 1, 이미 그만큼(또는 더) 알렸으면 0.
     *
     * <h3>★★ 왜 "다르면" 이 아니라 "더 크면" 인가</h3>
     * 비용 합은 원래 줄지 않지만, 줄어 보일 수 있는 길이 있다 — 단계 기록을 지우는 회수 작업이나
     * 사람이 손으로 지운 줄. 그때 "다르면 보낸다" 로 두면 $30 까지 알린 뒤 합계가 $25 로 내려갔다가
     * 다시 오를 때 <b>$30 을 한 번 더</b> 보낸다. 되돌아가지 않는 방향으로만 올린다.
     *
     * ★ {@code cast(... as numeric)} 로 쓴다 — 포스트그레스 식 {@code ::numeric} 은 이름 붙인
     *   매개변수({@code :now})와 글자가 겹쳐 해석이 갈린다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into zzal_alert_state (alert_key, last_value, sent_at)
                 values (:key, :value, :now)
            on conflict (alert_key) do update
                    set last_value = excluded.last_value, sent_at = excluded.sent_at
                  where cast(zzal_alert_state.last_value as numeric) < cast(excluded.last_value as numeric)
            """, nativeQuery = true)
    int claimGreater(@Param("key") String key, @Param("value") String value, @Param("now") Instant now);
}
