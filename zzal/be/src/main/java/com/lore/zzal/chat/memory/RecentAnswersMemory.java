package com.lore.zzal.chat.memory;

import com.lore.zzal.chat.ZzalChatCall;
import com.lore.zzal.chat.ZzalChatCallRepository;
import com.lore.zzal.chat.session.Speaker;
import com.lore.zzal.chat.session.ZzalChatTurn;
import com.lore.zzal.chat.session.ZzalChatTurnRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * v1 기억 — 사용자가 한 말 최근 5개(정본 10장). 별도 표가 없다.
 * ★ 새 대화(턴 표)와 옛 부름(답 칸)을 합쳐 시각 순으로 자른다 — 대화형으로 바뀐 날 이전의 답도 기억에 남는다.
 */
@Component
public class RecentAnswersMemory implements MemoryProvider {

    private static final int SIZE = 5;

    private final ZzalChatTurnRepository turns;
    private final ZzalChatCallRepository legacy;

    public RecentAnswersMemory(ZzalChatTurnRepository turns, ZzalChatCallRepository legacy) {
        this.turns = turns;
        this.legacy = legacy;
    }

    @Override
    public List<Memory> recall(Long petId, RecallQuery query) {
        List<Memory> all = new ArrayList<>();
        for (ZzalChatTurn t : turns.findTop5ByPetIdAndSpeakerOrderByCreatedAtDescIdDesc(petId, Speaker.USER)) {
            all.add(Memory.recentAnswer(t.getLine(), t.getCreatedAt()));
        }
        for (ZzalChatCall c : legacy.findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(petId)) {
            all.add(Memory.recentAnswer(c.getAnswer(), c.getAnsweredAt()));
        }
        all.sort(Comparator.comparing(Memory::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return all.size() > SIZE ? List.copyOf(all.subList(0, SIZE)) : List.copyOf(all);
    }

    @Override
    public void remember(Long petId, ZzalChatTurn userTurn) {
        // 사용자 턴 행이 곧 기억이다 — 더 할 일이 없다.
    }
}
