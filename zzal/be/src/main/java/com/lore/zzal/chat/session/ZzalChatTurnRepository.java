package com.lore.zzal.chat.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ZzalChatTurnRepository extends JpaRepository<ZzalChatTurn, Long> {

    List<ZzalChatTurn> findBySessionIdOrderByIdxAsc(Long sessionId);

    /** 기억 칩 — 사용자 턴 최근 5개. */
    List<ZzalChatTurn> findTop5ByPetIdAndSpeakerOrderByCreatedAtDescIdDesc(Long petId, Speaker speaker);

    /** 이 펫에게 사용자가 마지막으로 한 말(판 무관). */
    java.util.Optional<ZzalChatTurn> findFirstByPetIdAndSpeakerOrderByCreatedAtDescIdDesc(Long petId, Speaker speaker);

    /** 이 판이 아닌 판에서 사용자가 마지막으로 한 말 — "지난 대화 마지막 말". */
    java.util.Optional<ZzalChatTurn> findFirstByPetIdAndSpeakerAndSessionIdNotOrderByCreatedAtDescIdDesc(
            Long petId, Speaker speaker, Long sessionId);

    /** 답을 받은 질문 항목들(사용자 턴에 옮겨 적힌 것). */
    @Query("select distinct t.questionItem from ZzalChatTurn t "
            + "where t.petId = :petId and t.speaker = com.lore.zzal.chat.session.Speaker.USER and t.questionItem is not null")
    List<QuestionItem> answeredItems(@Param("petId") Long petId);
}
