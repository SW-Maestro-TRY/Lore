package com.lore.zzal.generation.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * 이미지를 만들어 주는 곳.
 *
 * ★ 단계에서 분리한 이유 — 시트와 격자가 **똑같은 API** 를 부른다. 인증·요청 조립·
 *   응답 파싱·S3 업로드·비용 계산이 두 곳에 흩어지면 고칠 때마다 두 곳을 고쳐야 한다.
 *   그리고 제공자를 갈아탈 때(다른 모델·다른 회사) 여기만 바꾸면 된다.
 *
 * ★★ 이 구현은 **자동으로 다시 부르지 않는다.** 많은 HTTP 클라이언트가 실패하면 알아서
 *   재시도하는데, 이미지 생성에서 그러면 한 번 부른 줄 알았는데 돈이 두 배로 나가고
 *   우리 기록(zzal_gen_step)에는 한 줄만 남아 비용 집계가 실제보다 적어진다.
 *   다시 할지는 위층(GenerationRunner)이 기록을 남기며 판단한다.
 */
public interface ImageClient {

    /**
     * @param prompt        무엇을 그릴지
     * @param refImageKeys  참고할 이미지들의 S3 키(원본·시트 등)
     * @param outputKey     결과를 올릴 S3 키
     */
    Result generate(String prompt, List<String> refImageKeys, String outputKey, ModelSpec spec) throws Exception;

    record Result(String imageKey, BigDecimal costUsd) {}
}
