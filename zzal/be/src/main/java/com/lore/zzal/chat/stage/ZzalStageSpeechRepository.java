package com.lore.zzal.chat.stage;
import com.lore.zzal.chat.prompt.ConversationScene;import org.springframework.data.jpa.repository.*;import java.util.*;
public interface ZzalStageSpeechRepository extends JpaRepository<ZzalStageSpeech,Long>{
 Optional<ZzalStageSpeech> findByPetIdAndIntroVersionAndStepAndPhase(Long petId,int introVersion,ConversationScene.Step step,ConversationScene.Beat phase);
 List<ZzalStageSpeech> findByPetIdAndIntroVersion(Long petId,int introVersion);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select s from ZzalStageSpeech s where s.id=:id")
 Optional<ZzalStageSpeech> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
