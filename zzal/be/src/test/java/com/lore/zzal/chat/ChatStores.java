package com.lore.zzal.chat;

import com.lore.zzal.chat.session.Speaker;
import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import com.lore.zzal.chat.session.ZzalChatTurn;
import com.lore.zzal.chat.session.ZzalChatTurnRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 시험용 — 옛 부름·판·턴 세 표를 메모리 목록으로 흉내 낸다(DB 없이 저장 행을 그대로 본다). */
public final class ChatStores {

    public final List<ZzalChatCall> calls = new ArrayList<>();
    public final List<ZzalChatSession> sessions = new ArrayList<>();
    public final List<ZzalChatTurn> turns = new ArrayList<>();
    public final ZzalChatCallRepository callRepo = mock(ZzalChatCallRepository.class);
    public final ZzalChatSessionRepository sessionRepo = mock(ZzalChatSessionRepository.class);
    public final ZzalChatTurnRepository turnRepo = mock(ZzalChatTurnRepository.class);
    private long ids = 100;

    public ChatStores() {
        when(callRepo.findByPetIdAndDayOfAndSlot(anyLong(), any(), any())).thenAnswer(inv -> calls.stream()
                .filter(c -> c.getPetId().equals(inv.getArgument(0)) && c.getDayOf().equals(inv.getArgument(1))
                        && c.getSlot() == inv.getArgument(2)).findFirst());
        when(callRepo.findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(anyLong())).thenAnswer(inv ->
                calls.stream().filter(c -> c.getPetId().equals(inv.getArgument(0)) && c.isAnswered())
                        .sorted((a, b) -> b.getAnsweredAt().compareTo(a.getAnsweredAt())).limit(5).toList());

        when(sessionRepo.save(any())).thenAnswer(inv -> {
            ZzalChatSession s = inv.getArgument(0);
            if (s.getId() == null) {
                ReflectionTestUtils.setField(s, "id", ids++);
                sessions.add(s);
            }
            return s;
        });
        when(sessionRepo.findByPetIdAndDayOfAndSlot(anyLong(), any(), any())).thenAnswer(inv -> sessions.stream()
                .filter(s -> s.getPetId().equals(inv.getArgument(0)) && s.getDayOf().equals(inv.getArgument(1))
                        && s.getSlot() == inv.getArgument(2)).findFirst());
        when(sessionRepo.findAllById(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = new java.util.HashSet<>();
            ((Iterable<Long>) inv.getArgument(0)).forEach(ids::add);
            return sessions.stream().filter(s -> ids.contains(s.getId())).toList();
        });
        when(sessionRepo.findByPetIdAndCloseReasonIsNull(anyLong())).thenAnswer(inv -> sessions.stream()
                .filter(s -> s.getPetId().equals(inv.getArgument(0)) && !s.isClosed()).toList());

        when(turnRepo.save(any())).thenAnswer(inv -> {
            ZzalChatTurn t = inv.getArgument(0);
            if (t.getId() == null) {
                ReflectionTestUtils.setField(t, "id", ids++);
                turns.add(t);
            }
            return t;
        });
        when(turnRepo.findBySessionIdOrderByIdxAsc(anyLong())).thenAnswer(inv -> turns.stream()
                .filter(t -> t.getSessionId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparingInt(ZzalChatTurn::getIdx)).toList());
        when(turnRepo.findByPetIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(anyLong(), any())).thenAnswer(inv ->
                turns.stream().filter(t -> t.getPetId().equals(inv.getArgument(0))
                                && !t.getCreatedAt().isBefore(inv.getArgument(1)))
                        .sorted(Comparator.comparing(ZzalChatTurn::getCreatedAt).thenComparing(ZzalChatTurn::getId))
                        .toList());
        when(turnRepo.findFirstByPetIdAndSpeakerOrderByCreatedAtDescIdDesc(anyLong(), any())).thenAnswer(inv ->
                users(inv.getArgument(0), inv.getArgument(1), null).findFirst());
        when(turnRepo.answeredItems(anyLong())).thenAnswer(inv -> turns.stream()
                .filter(t -> t.getPetId().equals(inv.getArgument(0)) && t.getSpeaker() == Speaker.USER
                        && t.getQuestionItem() != null)
                .map(ZzalChatTurn::getQuestionItem).distinct().toList());
    }

    private java.util.stream.Stream<ZzalChatTurn> users(Long petId, Speaker sp, Long notSession) {
        return turns.stream()
                .filter(t -> t.getPetId().equals(petId) && t.getSpeaker() == sp
                        && (notSession == null || !t.getSessionId().equals(notSession)))
                .sorted(Comparator.comparing(ZzalChatTurn::getCreatedAt).thenComparing(ZzalChatTurn::getId).reversed());
    }

    public Optional<ZzalChatSession> session(ChatSlot slot) {
        return sessions.stream().filter(s -> s.getSlot() == slot).findFirst();
    }

    public List<ZzalChatTurn> turnsOf(ZzalChatSession s) {
        return turns.stream().filter(t -> t.getSessionId().equals(s.getId()))
                .sorted(Comparator.comparingInt(ZzalChatTurn::getIdx)).toList();
    }

    /** 이 판의 마지막 펫 턴. */
    public ZzalChatTurn lastPet(ChatSlot slot) {
        List<ZzalChatTurn> ts = turnsOf(session(slot).orElseThrow());
        return ts.stream().filter(ZzalChatTurn::isPet).reduce((a, b) -> b).orElseThrow();
    }
}
