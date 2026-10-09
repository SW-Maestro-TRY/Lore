package com.lore.zzal.chat.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ZzalChatTurnRepository extends JpaRepository<ZzalChatTurn, Long> {

    List<ZzalChatTurn> findBySessionIdOrderByIdxAsc(Long sessionId);

    /** 기억(#709) — 이 펫의 턴 중 {@code from} 이후 것 전부(펫·사용자 양쪽), 오래된 순. */
    List<ZzalChatTurn> findByPetIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(Long petId, Instant from);

    /** 이 펫에게 사용자가 마지막으로 한 말(판 무관). */
    java.util.Optional<ZzalChatTurn> findFirstByPetIdAndSpeakerOrderByCreatedAtDescIdDesc(Long petId, Speaker speaker);

    /** 답을 받은 질문 항목들(사용자 턴에 옮겨 적힌 것). */
    @Query("select distinct t.questionItem from ZzalChatTurn t "
            + "where t.petId = :petId and t.speaker = com.lore.zzal.chat.session.Speaker.USER and t.questionItem is not null")
    List<QuestionItem> answeredItems(@Param("petId") Long petId);
}
