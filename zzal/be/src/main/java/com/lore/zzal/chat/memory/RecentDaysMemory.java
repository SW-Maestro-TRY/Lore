package com.lore.zzal.chat.memory;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import com.lore.zzal.chat.session.ZzalChatTurn;
import com.lore.zzal.chat.session.ZzalChatTurnRepository;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.ZzalRules;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v1.5 기억(#709) — <b>오늘 포함 최근 {@value ZzalRules#CHAT_MEMORY_DAYS}일</b>(한국 날짜)의 판 턴 전부, 펫·사용자 양쪽.
 * 상한은 없다 — 하루 최대 3판 × 5왕복이라 3일이어도 90줄 안팎이다(상훈 결정 10/10 "대화 3일치").
 *
 * <h3>순서</h3>
 * 판이 시작된 순(그 판 첫 턴의 시각), 판 안에서는 턴 번호 순. 같은 요청에서 저장되는 사용자·펫 턴은 시각이 같아
 * 시각만으로는 순서가 안 선다.
 *
 * ★ 옛 부름({@code zzal_chat_call})은 읽지 않는다 — 옛 표 읽기 경로는 따로 걷는 중이다.
 */
@Component
public class RecentDaysMemory implements MemoryProvider {

    private final ZzalChatTurnRepository turns;
    private final ZzalChatSessionRepository sessions;

    public RecentDaysMemory(ZzalChatTurnRepository turns, ZzalChatSessionRepository sessions) {
        this.turns = turns;
        this.sessions = sessions;
    }

    /** 기억이 시작되는 시각 — (오늘 − 2일) 0시, 한국 날짜. */
    public static Instant since(Instant now) {
        return AwakeClock.dateOf(now).minusDays(ZzalRules.CHAT_MEMORY_DAYS - 1L).atStartOfDay(ZzalRules.ZONE).toInstant();
    }

    @Override
    public List<Memory> recall(Long petId, RecallQuery query) {
        List<ZzalChatTurn> ts = turns.findByPetIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(petId,
                since(query.now()));
        if (ts.isEmpty()) {
            return List.of();
        }
        // 지금(펫 시계) 뒤의 줄은 없다 — 시계를 되돌린 시험 같은 자리에서 미래 줄이 섞이지 않게.
        ts = ts.stream().filter(t -> !t.getCreatedAt().isAfter(query.now())).toList();
        Set<Long> ids = ts.stream().map(ZzalChatTurn::getSessionId).collect(Collectors.toSet());
        Map<Long, ChatSlot> slots = new HashMap<>();
        for (ZzalChatSession s : sessions.findAllById(ids)) {
            slots.put(s.getId(), s.getSlot());
        }
        // 판별로 묶는다 — 첫 턴의 시각 순(LinkedHashMap 은 처음 본 순서를 지킨다; ts 가 시각 순이다).
        Map<Long, List<ZzalChatTurn>> bySession = new LinkedHashMap<>();
        for (ZzalChatTurn t : ts) {
            bySession.computeIfAbsent(t.getSessionId(), k -> new ArrayList<>()).add(t);
        }
        List<Memory> out = new ArrayList<>();
        for (Map.Entry<Long, List<ZzalChatTurn>> e : bySession.entrySet()) {
            e.getValue().sort(Comparator.comparingInt(ZzalChatTurn::getIdx));
            for (ZzalChatTurn t : e.getValue()) {
                out.add(new Memory(t.getLine(), t.isPet() ? Memory.PET_LINE : Memory.USER_LINE, t.getCreatedAt(),
                        t.getSessionId(), slots.get(t.getSessionId())));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public void remember(Long petId, ZzalChatTurn userTurn) {
        // 턴 행이 곧 기억이다 — 더 할 일이 없다.
    }
}
