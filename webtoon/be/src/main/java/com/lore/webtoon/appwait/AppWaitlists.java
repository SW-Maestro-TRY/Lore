package com.lore.webtoon.appwait;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 앱 출시 알림 신청을 받는다(#981). 같은 이메일로 여러 번 눌러도 한 줄만 남는다.
 */
@Service
public class AppWaitlists {

    /** 화면과 같은 느슨한 모양 검사. 진짜 주소인지는 출시 메일이 가 봐야 안다. */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> DEVICES = Set.of("ios", "android");
    private static final Set<String> LANGS = Set.of("ko", "en", "ja", "zh");

    private final AppWaitlistRepository rows;

    public AppWaitlists(AppWaitlistRepository rows) {
        this.rows = rows;
    }

    /** @return 이미 신청돼 있던 이메일이면 true */
    @Transactional
    public boolean join(String email, Long userId, String uid, String device, String source, String lang) {
        String clean = email == null ? "" : email.trim();
        if (clean.isEmpty() || clean.length() > 320 || !EMAIL.matcher(clean).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이메일 주소를 확인해 주세요");
        }
        String key = clean.toLowerCase(Locale.ROOT);
        var found = rows.findByEmailKey(key);
        if (found.isPresent()) {
            found.get().attachUser(userId);
            return true;
        }
        try {
            rows.saveAndFlush(new AppWaitlist(clean, key, userId, cut(uid, 64), DEVICES.contains(device) ? device : null,
                    cut(source, 40), LANGS.contains(lang) ? lang : "ko", Instant.now()));
            return false;
        } catch (DataIntegrityViolationException raced) {
            return true; // 같은 이메일이 동시에 두 번 들어왔다 — 먼저 들어온 줄이 남는다
        }
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
