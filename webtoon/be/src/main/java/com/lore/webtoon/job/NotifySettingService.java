package com.lore.webtoon.job;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「웹툰 완성 메일」 켜고 끄기. {@link NotifySetting} 머리 주석대로 행이
 * 없으면 켜진 것으로 본다.
 */
@Service
public class NotifySettingService {

    private final NotifySettingRepository settings;

    public NotifySettingService(NotifySettingRepository settings) {
        this.settings = settings;
    }

    /** 지금 켜져 있는가. 행이 없으면(한 번도 안 건드렸으면) true. */
    public boolean isOn(Long userId) {
        return settings.findById(userId).map(NotifySetting::isNotifyOnComplete).orElse(true);
    }

    /** 켜고 끈다. 행이 없으면 새로 만든다. */
    @Transactional
    public boolean set(Long userId, boolean on) {
        NotifySetting row = settings.findById(userId).orElseGet(() -> new NotifySetting(userId, on));
        row.setNotifyOnComplete(on);
        settings.save(row);
        return on;
    }
}
