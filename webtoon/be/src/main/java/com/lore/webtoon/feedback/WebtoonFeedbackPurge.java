package com.lore.webtoon.feedback;

import com.lore.common.retention.UserDataPurge;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴한 사람의 설문 답(자유 의견 · 연락처 포함)을 지운다. 계정을 지우는 쪽이 도메인마다
 * 이 인터페이스를 찾아 부른다. 로그인 없이 낸 짧은 설문은 계정과 이어지지 않아 남는다 —
 * 거기에는 선택지 답과 브라우저 번호뿐이다.
 */
@Component
public class WebtoonFeedbackPurge implements UserDataPurge {

    private final WebtoonFeedbackRepository feedback;

    public WebtoonFeedbackPurge(WebtoonFeedbackRepository feedback) {
        this.feedback = feedback;
    }

    @Override
    public String domain() {
        return "webtoon-feedback";
    }

    @Override
    @Transactional
    public int purge(Long userId) {
        return feedback.deleteByUser(userId);
    }
}
