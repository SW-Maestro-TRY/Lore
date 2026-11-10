package com.lore.zzal.openai;

import com.lore.zzal.generation.client.ProviderUsageContext;
import com.lore.zzal.generation.client.ProviderUsageRecorder;

/**
 * 창구가 남기는 호출 기록(설계 1-3·2-3). 사용량 기록기({@link ProviderUsageRecorder})가 이것도 구현하면
 * 창구가 보내기 직전에 {@link #started} 를, 끝에 {@link #finished} 를 부른다. 구현하지 않으면 예전처럼
 * 끝 기록만 남는다(시험의 목 기록기 등).
 */
public interface ProviderCallLog {

    /**
     * 호출 하나의 꼬리표.
     *
     * @param callId          시작 행과 사용량 행을 잇는 번호(사용량 행의 event_id 와 같다)
     * @param lane            줄 이름
     * @param queueWaitMs     자리를 기다린 시간
     * @param inFlightAtStart 자리를 얻은 순간 그 줄에서 나가 있던 요청 수(자기 포함)
     * @param links           기능·펫·잡·단계 — 보낸 스레드에서 읽어 둔 것
     */
    record Call(String callId, String lane, String model, long queueWaitMs, int inFlightAtStart,
                ProviderUsageContext.Links links) {
    }

    /** 보내기 직전. 따로 커밋한다(REQUIRES_NEW). 실패해도 호출은 막지 않는다 — 부르는 쪽이 삼킨다. */
    void started(Call call) throws Exception;

    /** 끝. 사용량 한 행에 줄 지표를 함께 싣는다. */
    void finished(ProviderUsageRecorder.Observation observation, Call call) throws Exception;
}
