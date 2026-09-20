package com.lore.webtoon.character;

import com.lore.webtoon.credit.BrowserLink;
import com.lore.webtoon.credit.BrowserLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 지금 묻는 사람이 누구인가 — 계정과 브라우저를 함께 본다.
 *
 * <h2>왜 둘 다 봐야 하나</h2>
 *
 * 캐릭터는 <b>로그인 없이도 만들 수 있다.</b> 그래서 계정만 보면 게스트가
 * 만든 것을 누구 것이라고 말할 수가 없고, 브라우저만 보면 기기를 바꾼 사람이
 * 자기 것을 잃는다.
 *
 * <h2>로그인하면 전에 만든 것이 따라온다</h2>
 *
 * {@link BrowserLink} 가 (계정 ↔ 브라우저 uid)를 이어 둔다. 로그인한 사람의
 * uid 목록을 여기에 얹으면, <b>로그인 전에 만든 캐릭터가 로그인 뒤에도 그대로
 * 내 것</b>이다 — 줄을 고쳐 옮기지 않아도 된다. 작품이 이미 같은 방식이라
 * 같은 표를 그대로 쓴다.
 *
 * <h2>⚠️ 소유 증명은 아니다</h2>
 *
 * uid 는 브라우저가 들고 다니는 값이라 남의 것을 적어 보낼 수 있다. 이것이
 * 하는 일은 「내 것을 모아 보여 주는 것」이지 증명이 아니다 — 왜 그래도 이
 * 길인지는 {@link BrowserLink} 머리 주석에 있다.
 */
@Service
public class CharacterOwner {

    /**
     * 아무와도 안 맞는 값. 목록이 비면 {@code in :uids} 가 문법상 곤란해지고,
     * 무엇보다 <b>빈 값이 들어가면 uid 가 없는 줄이 전부 걸린다</b> — 그래서
     * 실제 uid 로는 절대 나올 수 없는 것을 하나 넣어 둔다.
     */
    private static final String NONE = "-없음-";

    private final BrowserLinkRepository links;

    public CharacterOwner(BrowserLinkRepository links) {
        this.links = links;
    }

    /**
     * 이 사람을 가리키는 브라우저 uid 들.
     *
     * @param userId 로그인했으면 계정 번호. 안 했으면 {@code null}
     * @param uid    화면이 보낸 이 브라우저의 값. 없을 수 있다
     */
    @Transactional(readOnly = true)
    public List<String> uidsOf(Long userId, String uid) {
        Set<String> out = new LinkedHashSet<>();
        if (uid != null && !uid.isBlank()) {
            out.add(uid.trim());
        }
        if (userId != null) {
            for (BrowserLink one : links.findByUserId(userId)) {
                if (one.getBrowserUid() != null && !one.getBrowserUid().isBlank()) {
                    out.add(one.getBrowserUid());
                }
            }
        }
        List<String> all = new ArrayList<>(out);
        all.add(NONE);
        return all;
    }
}
