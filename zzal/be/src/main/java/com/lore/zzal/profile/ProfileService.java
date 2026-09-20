package com.lore.zzal.profile;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private final ZzalUserProfileRepository repository;

    public ProfileService(ZzalUserProfileRepository repository) {
        this.repository = repository;
    }

    /**
     * 답한 데까지 저장한다. 줄이 없으면 만든다.
     *
     * ★ 처음 답할 때와 두 번째 답할 때가 같은 호출이다. 화면이 "처음인가"를 따지지 않아도 된다.
     */
    @Transactional
    public ZzalUserProfile patch(Long userId, String callMe, String visitTime, String relation,
                                 String draws, String ageBand, String cameFrom, Instant now) {
        ZzalUserProfile profile = repository.findById(userId)
                .orElseGet(() -> repository.save(ZzalUserProfile.of(userId, now)));
        profile.patch(callMe, visitTime, relation, draws, ageBand, cameFrom, now);
        return profile;
    }

    /** 아직 한 문항도 안 답했으면 빈 줄을 만들어 준다 — 화면이 null 을 따로 다루지 않게. */
    @Transactional
    public ZzalUserProfile get(Long userId, Instant now) {
        return repository.findById(userId)
                .orElseGet(() -> repository.save(ZzalUserProfile.of(userId, now)));
    }
}
