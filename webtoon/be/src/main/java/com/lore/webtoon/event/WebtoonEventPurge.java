package com.lore.webtoon.event;

import com.lore.common.retention.UserDataPurge;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴한 사람의 행동 기록을 지운다. 계정을 지우는 쪽({@code AccountPurge})이 도메인마다
 * 이 인터페이스를 찾아 부른다.
 *
 * 로그인 전에 남은 줄(계정 번호 없이 브라우저 번호만 있는 줄)은 누구의 것인지 이어 볼
 * 계정이 사라지므로 그대로 두고, 1년이 지나면 {@link EventRetention} 이 지운다.
 */
@Component
public class WebtoonEventPurge implements UserDataPurge {

    private final WebtoonEventRepository events;

    public WebtoonEventPurge(WebtoonEventRepository events) {
        this.events = events;
    }

    @Override
    public String domain() {
        return "webtoon-event";
    }

    @Override
    @Transactional
    public int purge(Long userId) {
        return events.deleteByUser(userId);
    }
}
