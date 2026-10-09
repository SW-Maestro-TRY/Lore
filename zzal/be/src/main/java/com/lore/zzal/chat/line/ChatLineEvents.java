package com.lore.zzal.chat.line;

import com.lore.common.analytics.AnalyticsService;
import com.lore.common.analytics.dto.EventRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 이벤트 {@code zzal_chat_llm} — 대사 한 줄마다 생성기·폴백 사유·걸린 시간.
 *
 * <h3>★ props 는 이미 허용된 키만 쓴다</h3>
 * {@code AnalyticsService} 의 허용 키(common, 안 고침)만 쓴다 — {@code action}=턴 종류(first_meet·continue·close …),
 * {@code step}=판 안의 몇 번째 펫 턴, {@code code}=판 종류(baby·daily …), {@code type}=생성기(template·llm),
 * {@code reason}=폴백 사유(성공이면 "ok"), {@code ms}=걸린 시간.
 *
 * <h3>★ 커밋 <b>뒤에</b> 남긴다</h3>
 * 채팅 요청은 펫 행을 잠근 트랜잭션 안이다. 거기서 기록을 쓰다 실패하면 트랜잭션이 롤백 전용으로 바뀌어
 * 채팅이 통째로 실패할 수 있고, 반대로 채팅이 롤백되면 기록도 지워진다({@code HatchBlockLog} 와 같은 이유).
 * 그래서 트랜잭션이 끝난 뒤에, <b>새 트랜잭션(REQUIRES_NEW)</b>으로 남기고 무슨 일이 나든 삼킨다 —
 * {@code afterCompletion} 안에서 그냥 부르면 끝난 트랜잭션에 얹혀 저장이 안 될 수 있다(스프링 문서).
 */
@Component
public class ChatLineEvents {

    private static final Logger log = LoggerFactory.getLogger(ChatLineEvents.class);

    public static final String EVENT = "zzal_chat_llm";
    /** 서버가 남기는 줄의 익명 번호({@code HatchBlockLog.SERVER_ANON} 과 같은 값). */
    static final String SERVER_ANON = "00000000000000000000000000000000";

    private final AnalyticsService analytics;
    private final TransactionTemplate newTx;

    public ChatLineEvents(AnalyticsService analytics, PlatformTransactionManager txManager) {
        this.analytics = analytics;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String action, int step, String kind, String generator, String fallbackReason, long millis,
                       Long userId) {
        Map<String, Object> props = new HashMap<>();
        props.put("action", action);
        props.put("step", step);
        props.put("code", kind);
        props.put("type", generator);
        props.put("reason", fallbackReason == null ? "ok" : fallbackReason);
        props.put("ms", millis);
        Runnable send = () -> {
            try {
                newTx.executeWithoutResult(st -> analytics.collect(new EventRequests.Batch(null, null,
                                List.of(new EventRequests.Event(EVENT, System.currentTimeMillis(), null, props))),
                        SERVER_ANON, userId, null));
            } catch (RuntimeException e) {
                log.warn("채팅 대사 기록 실패 — {}", props, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
