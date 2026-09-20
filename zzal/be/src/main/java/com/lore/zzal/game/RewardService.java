package com.lore.zzal.game;

import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 보상을 준다 — 후기와 미니게임이 함께 쓴다.
 *
 * ★ 지금은 설정이 전부 {@link RewardKind#NONE} 이라 <b>아무 일도 하지 않는다.</b>
 *   그래도 지금 만들어 두는 이유는, 무엇을 줄지 정해졌을 때 <b>설정값만 바꾸면</b>
 *   두 기능에 동시에 붙게 하기 위해서다.
 */
@Service
public class RewardService {

    private static final Logger log = LoggerFactory.getLogger(RewardService.class);

    private final ZzalPetRepository petRepository;
    private final RewardKind feedbackReward;
    private final RewardKind gameWinReward;

    public RewardService(ZzalPetRepository petRepository,
                         @Value("${app.zzal.reward.feedback:NONE}") RewardKind feedbackReward,
                         @Value("${app.zzal.reward.game-win:NONE}") RewardKind gameWinReward) {
        this.petRepository = petRepository;
        this.feedbackReward = feedbackReward;
        this.gameWinReward = gameWinReward;
    }

    public void forFeedback(Long petId, Instant now) {
        grant(petId, feedbackReward, now);
    }

    public void forGameWin(Long petId, Instant now) {
        grant(petId, gameWinReward, now);
    }

    /** 이미 잠그고 정산한 펫에 바로 준다(게임 서비스가 트랜잭션 안에서 부른다 — 다시 읽으면 잠금이 두 번). */
    public void forGameWin(ZzalPet pet, Instant now) {
        switch (gameWinReward) {
            case FOOD -> pet.grantFood(now);
            case HAPPINESS -> pet.grantHappiness();
            case NONE -> {
            }
        }
    }

    @Transactional
    public void grant(Long petId, RewardKind kind, Instant now) {
        if (kind == RewardKind.NONE) {
            return;
        }
        ZzalPet pet = petRepository.findById(petId).orElse(null);
        if (pet == null) {
            return;
        }
        pet.settle(pet.now(now));
        switch (kind) {
            case FOOD -> pet.grantFood(pet.now(now));
            case HAPPINESS -> pet.grantHappiness();
            case NONE -> {
            }
        }
        log.info("보상 지급 — petId={} 무엇={}", petId, kind);
    }
}
