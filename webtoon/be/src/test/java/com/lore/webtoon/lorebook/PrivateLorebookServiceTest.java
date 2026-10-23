package com.lore.webtoon.lorebook;
import com.lore.common.exception.BusinessException;
import com.lore.webtoon.safety.SafetyGuard;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PrivateLorebookServiceTest {
 final PrivateLorebookRepository rows=mock(PrivateLorebookRepository.class);
 final PrivateLorebookService service=new PrivateLorebookService(rows,mock(SafetyGuard.class));
 Map<String,Object> form(){return new LinkedHashMap<>(Map.of("name","나만의 도서관","source","manual","fixed_settings","달이 뜨면 열린다","progress_state","","entries",List.of(),"confirmed",true));}
 PrivateLorebook row(){return new PrivateLorebook("mine",7L,"{\"name\":\"나만의 도서관\",\"confirmed\":true}");}
 @Test void guestsCannotListReadCreateUpdateOrDelete(){
  assertThatThrownBy(()->service.list(null)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.read("mine",null)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.create(null,form())).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.update("mine",null,form())).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.delete("mine",null)).isInstanceOf(BusinessException.class);
  verifyNoInteractions(rows);
 }
 @Test void listOnlyQueriesCurrentOwner(){when(rows.findAllByOwnerIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(row()));assertThat(service.list(7L)).singleElement().satisfies(b->assertThat(b).containsEntry("library_id","mine").doesNotContainKeys("owner_id","public","visibility"));verify(rows).findAllByOwnerIdOrderByUpdatedAtDesc(7L);verifyNoMoreInteractions(rows);}
 @Test void otherUserCannotReadUpdateDeleteEvenWithKnownId(){when(rows.findByIdAndOwnerId("mine",8L)).thenReturn(Optional.empty());assertThatThrownBy(()->service.read("mine",8L)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->service.update("mine",8L,form())).isInstanceOf(BusinessException.class);assertThatThrownBy(()->service.delete("mine",8L)).isInstanceOf(BusinessException.class);verify(rows,never()).save(any());verify(rows,never()).delete(any());}
 @Test void forgedOwnerIsIgnoredAndSaveReturnsPrivateSnapshot(){var body=form();body.put("owner_id",8L);body.put("id","someone-elses-id");when(rows.save(any())).thenAnswer(c->{PrivateLorebook r=c.getArgument(0);assertThat(r.ownerId).isEqualTo(7L);assertThat(r.id).isNotEqualTo("someone-elses-id");assertThat(r.bookJson).doesNotContain("owner_id");return r;});assertThat(service.create(7L,body)).containsKey("library_id").doesNotContainKeys("public","owner_id","visibility");}
 @Test void publicOrSharedRequestsAreRejected(){for(String key:List.of("public","visibility","is_public","shared")){var body=form();body.put(key,true);assertThatThrownBy(()->service.create(7L,body)).isInstanceOf(BusinessException.class).hasMessageContaining("공개");}verifyNoInteractions(rows);}
 @Test void ownerCanReadUpdateAndDelete(){when(rows.findByIdAndOwnerId("mine",7L)).thenReturn(Optional.of(row()));when(rows.save(any())).thenAnswer(c->c.getArgument(0));assertThat(service.read("mine",7L)).containsEntry("library_id","mine");assertThat(service.update("mine",7L,form())).containsEntry("name","나만의 도서관");service.delete("mine",7L);verify(rows).delete(any());}
 @Test void unconfirmedExtractionCannotBeSaved(){var body=form();body.put("confirmed",false);assertThatThrownBy(()->service.create(7L,body)).isInstanceOf(BusinessException.class);verifyNoInteractions(rows);}
}
