package com.lore.zzal.guard;

import com.lore.common.analytics.AnalyticsService;
import com.lore.common.analytics.dto.EventRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * <b>막혔다는 사실 자체</b>를 남긴다 — 행동 기록표({@code zzal_event}) 한 줄.
 *
 * <h3>★★ 왜 남기나</h3>
 * 막는 것만 하고 기록을 안 하면, 며칠 뒤 "사람들이 왜 안 만들었지" 를 아무도 답할 수 없다.
 * 상한이 너무 빡빡했는지, 한 사람이 두드린 것인지, 바깥이 막은 것인지가 <b>전부 같은 침묵</b>으로
 * 보인다. 알파의 목적이 측정이라, 막힌 자리는 성공한 자리만큼 중요한 데이터다.
 *
 * <h3>★★ {@code common/} 을 한 글자도 안 고친다</h3>
 * 이 표와 수집기는 {@code common/be/.../analytics} 에 있고 세 도메인이 함께 쓴다. 그래서
 * <b>이미 있는 서비스를 부르기만</b> 한다 — 새 표도, 새 칼럼도, 수집기 수정도 없다.
 * 쓸 수 있는 이유는 두 가지가 이미 맞아떨어지기 때문이다.
 * <ul>
 *   <li>{@code props} 의 {@code reason} 은 <b>이미 허용된 키</b>다
 *       ({@code AnalyticsService.ALLOWED_PROP_KEYS} — "generate_blocked·zzal_hatch_failed 의 열거된 사유")</li>
 *   <li>이벤트 이름 규칙이 소문자·숫자·밑줄이라 {@code zzal_hatch_blocked} 가 그대로 통과한다</li>
 * </ul>
 *
 * <h3>★★ 막은 트랜잭션 <b>밖에서</b> 불러야 한다</h3>
 * 막기는 예외로 끝나고 그 트랜잭션은 롤백된다. 안에서 적으면 <b>기록도 같이 지워진다</b> —
 * 막혔는데 아무 줄도 없는, 가장 알기 어려운 실패가 된다. 그래서 부르는 자리는
 * 컨트롤러(요청 트랜잭션이 이미 끝난 뒤)와 굽기 스레드다.
 *
 * <h3>★ 기록이 요청을 망가뜨리면 안 된다</h3>
 * 여기서 예외가 나가면 409(막힘) 가 500(서버 오류)으로 바뀐다. 사용자는 "고장" 으로 읽고
 * 화면은 재시도한다. 그래서 무슨 일이 나든 삼키고 로그에만 남긴다.
 */
@Component
public class HatchBlockLog {

    private static final Logger log = LoggerFactory.getLogger(HatchBlockLog.class);

    /** 이벤트 이름. 화면이 부르는 이름들과 같은 결({@code zzal_hatch_failed} · {@code zzal_hatch_abandoned}). */
    public static final String EVENT = "zzal_hatch_blocked";

    /**
     * 브라우저가 없는 자리(굽기 스레드)에서 쓰는 익명 번호.
     *
     * ★ 왜 비워 두지 않나 — {@code anon_id} 는 {@code nullable = false} 라 비우면 저장이 통째로
     *   실패한다. 모양(32자리 16진수)은 맞추되 <b>사람이 만들 수 없는 값</b>(전부 0)이라,
     *   나중에 집계할 때 "서버가 남긴 줄" 로 한눈에 갈린다.
     */
    public static final String SERVER_ANON = "00000000000000000000000000000000";

    private final AnalyticsService analytics;

    public HatchBlockLog(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /**
     * 한 줄 남긴다.
     *
     * @param anonId 브라우저의 익명 번호. 없으면 {@link #SERVER_ANON}
     * @param userId 로그인한 사람. 비로그인이면 null
     * @param path   어느 화면에서 막혔나. 몰라도 된다(null)
     */
    public void record(HatchBlock block, String anonId, Long userId, String path, Instant now) {
        try {
            EventRequests.Event event = new EventRequests.Event(
                    EVENT,
                    now.toEpochMilli(),
                    path,
                    Map.of("reason", block.reason()));
            analytics.collect(new EventRequests.Batch(null, null, List.of(event)),
                    anonId == null || anonId.isBlank() ? SERVER_ANON : anonId,
                    userId,
                    null);
        } catch (RuntimeException e) {
            // 기록을 잃는 것과 요청을 망치는 것 중에서는 잃는 쪽이 낫다.
            log.warn("부화 막힘 기록 실패 — reason={} userId={}", block.reason(), userId, e);
        }
    }
}
