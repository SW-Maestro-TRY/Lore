package com.lore.zzal.chat.delivery;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.springframework.stereotype.Component;

/** 펫쓰기잠금을 가진 답 트랜잭션 안에서만 호출한다. 보상시각·상한 확대 없음. */
@Component
public class ChatRewardLedger {
    private final ZzalChatRewardRepository rewards;
    private final ZzalChatSessionRepository legacy;
    public ChatRewardLedger(ZzalChatRewardRepository rewards,ZzalChatSessionRepository legacy) {
        this.rewards=rewards;this.legacy=legacy;
    }
    public boolean claim(ZzalPet pet,ZzalChatSession session,java.time.Instant now) {
        boolean baby=pet.isInTutorial();
        ChatSlot slot=baby?ChatSlot.BABY:ChatSlot.windowAt(now.atZone(pet.zone()).toLocalTime());
        if(slot==null) return false;
        // 튜토리얼 부름은 부화일 열쇠(KST 고정, ChatSessionKeys), 평소 부름은 펫 시간대의 오늘.
        java.time.LocalDate day=baby?com.lore.zzal.chat.ChatSessionKeys.hatchDay(pet):AwakeClock.dateOf(now,pet.zone());
        if(rewards.existsByPetIdAndDayOfAndSlot(pet.getId(),day,slot)) return false;
        if(legacy.findByPetIdAndDayOfAndSlot(pet.getId(),day,slot)
                .map(ZzalChatSession::isRewardGiven).orElse(false)) return false;
        rewards.save(new ZzalChatReward(pet.getId(),day,slot,now));
        return true;
    }
}
