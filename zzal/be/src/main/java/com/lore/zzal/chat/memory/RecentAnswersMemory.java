package com.lore.zzal.chat.memory;

import com.lore.zzal.chat.ZzalChatCall;
import com.lore.zzal.chat.ZzalChatCallRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v1 기억 — 답한 부름 최근 5개를 그대로(정본 10장 "최근 5개"). 별도 표가 없다.
 */
@Component
public class RecentAnswersMemory implements MemoryProvider {

    private final ZzalChatCallRepository callRepository;

    public RecentAnswersMemory(ZzalChatCallRepository callRepository) {
        this.callRepository = callRepository;
    }

    @Override
    public List<Memory> recall(Long petId, RecallQuery query) {
        return callRepository.findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(petId).stream()
                .map(c -> Memory.recentAnswer(c.getAnswer(), c.getAnsweredAt()))
                .toList();
    }

    @Override
    public void remember(Long petId, ZzalChatCall answered) {
        // 답한 행이 곧 기억이다 — 더 할 일이 없다.
    }
}
