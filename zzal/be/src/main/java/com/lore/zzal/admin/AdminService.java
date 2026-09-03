package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.admin.dto.AdminResponses;
import com.lore.zzal.motion.HumanVerdict;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 구워진 움짤을 상훈님이 눈으로 보고 판정하는 일.
 *
 * <h3>★ 판정을 눌러도 사용자 화면은 안 바뀐다</h3>
 * {@link ZzalMotion#open(Instant)} 은 상훈님 확인 <b>전에</b> 모션을 연다(2026-09-03 확정).
 * 밤에 재운 사용자가 아침에 깼는데 상훈님이 주무시는 동안 갇히면 안 되기 때문이다.
 * 그래서 여기서 {@code REGENERATE} 를 눌러도 <b>사용자는 계속 그 움짤을 보고 있다.</b>
 *
 * 지금은 이게 맞다 — 되돌리면 도감에서 칸이 사라져 "배운 게 없어졌다" 가 되고, 그건
 * 어설픈 그림보다 나쁘다. 대신 화면에 그렇게 적어 둔다. 안 적으면 누르고 나서
 * 바뀐 줄 아시게 되고, 그 오해는 판정 자체를 못 믿게 만든다.
 *
 * <h3>★ 실험 판정 원장과 절대 섞지 않는다</h3>
 * 여기는 <b>운영</b>이다. 모양이 비슷하다고 실험 쪽 도구와 합치면, 한쪽 기준을 고칠 때
 * 다른 쪽이 조용히 따라 바뀐다(2026-09-03 지시).
 */
@Service
public class AdminService {

    private final AdminGuard adminGuard;
    private final ZzalMotionRepository motionRepository;

    public AdminService(AdminGuard adminGuard, ZzalMotionRepository motionRepository) {
        this.adminGuard = adminGuard;
        this.motionRepository = motionRepository;
    }

    /**
     * 아직 안 본 것들.
     *
     * ★ 그림이 없는 행은 걸러낸다. 굽는 중(PENDING)·실패(FAILED)한 자리도 사람 판정이
     *   비어 있어 같은 조회에 딸려 오는데, <b>볼 그림이 없는 것을 목록에 띄우면</b>
     *   상훈님이 빈 칸 앞에서 판단할 수 없는 판정을 강요당한다. 걸러내는 것을 DB 질의가
     *   아니라 여기서 하는 이유는, 지금 대기열이 수십 건 규모라 질의를 하나 더 만들
     *   값어치가 없어서다(대기열이 커지면 그때 질의로 내린다).
     */
    @Transactional(readOnly = true)
    public List<AdminResponses.Pending> pending(Long userId) {
        adminGuard.require(userId);
        return motionRepository.findByHumanVerdictIsNullOrderByIdAsc().stream()
                .filter(m -> m.getImageKey() != null && !m.getImageKey().isBlank())
                .map(AdminResponses.Pending::from)
                .toList();
    }

    /**
     * 판정을 받아 적는다. 게이트 판정은 그대로 남는다 — 둘을 비교하는 것이 이 표의 목적이다.
     *
     * ★ 이미 본 것을 다시 눌러도 막지 않는다. 잘못 누른 판정을 고칠 길이 없으면
     *   틀린 채로 남고, 그 틀린 값이 게이트 일치율을 오염시킨다.
     */
    @Transactional
    public void review(Long userId, Long motionId, HumanVerdict verdict, String note) {
        adminGuard.require(userId);
        ZzalMotion motion = motionRepository.findById(motionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그 모션을 찾을 수 없어요"));
        motion.review(verdict, note, Instant.now());
    }
}
