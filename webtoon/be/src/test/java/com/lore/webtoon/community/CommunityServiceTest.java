package com.lore.webtoon.community;
import com.lore.common.exception.BusinessException;
import com.lore.webtoon.Admins;
import com.lore.webtoon.work.*;
import com.lore.webtoon.safety.SafetyGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommunityServiceTest {
    JdbcTemplate db=mock(JdbcTemplate.class);
    WorkLedger works=mock(WorkLedger.class);
    SeriesIdentity series=mock(SeriesIdentity.class);
    CommunityService service=new CommunityService(db,works,series,mock(Admins.class),mock(SafetyGuard.class));
    @Test void rejectsGuestBeforeDatabaseMutation() {
        assertThatThrownBy(()->service.subscribe("r",null,true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.addComment("r",null,"hello")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(db);
    }
    @Test void unknownWorkIsNotPublic() {
        when(works.find("r")).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.comments("r",1L,0)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(db);
    }
    @Test void privateWorkIsNotReadableEvenByAuthorInCommunity() {
        var work=mock(WebtoonWork.class);when(work.isPublic()).thenReturn(false);
        when(works.find("r")).thenReturn(Optional.of(work));
        assertThatThrownBy(()->service.comments("r",1L,0)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(db);
    }
    @Test void validatesBoundedText() {
        assertThat(CommunityService.text(" 감상 ",1000)).isEqualTo("감상");
        assertThatThrownBy(()->CommunityService.text(" ",1000)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->CommunityService.text("a".repeat(1001),1000)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->CommunityService.text(null,1000)).isInstanceOf(BusinessException.class);
    }
}
