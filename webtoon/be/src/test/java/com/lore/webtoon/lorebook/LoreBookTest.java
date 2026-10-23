package com.lore.webtoon.lorebook;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.lore.common.exception.BusinessException;
import static org.assertj.core.api.Assertions.*;
class LoreBookTest {
 @Test void detachedBookLeavesNothing(){assertThat(LoreBook.clean(null,true)).isEmpty();assertThat(LoreBook.clean(Map.of(),true)).isEmpty();}
 @Test void unconfirmedBookCannotGenerate(){assertThatThrownBy(()->LoreBook.clean(Map.of("fixed_settings","test"),true)).isInstanceOf(BusinessException.class);}
 @Test void onlyAllowedDataSurvives(){var b=LoreBook.clean(Map.of("confirmed",true,"path","/tmp/other","fixed_settings"," world ","entries",List.of(Map.of("title","a","content","b","enabled",false))),true);assertThat(b).doesNotContainKey("path");assertThat(b.get("fixed_settings")).isEqualTo("world");assertThat((List<?>)b.get("entries")).hasSize(1);}
 @Test void budgetAndEntryBoundsAreEnforced(){assertThatThrownBy(()->LoreBook.clean(Map.of("fixed_settings","x".repeat(8001)),false)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->LoreBook.clean(Map.of("entries",Collections.nCopies(25,Map.of())),false)).isInstanceOf(BusinessException.class);}
 @Test void collectionPreservesEachBookAndCombinesAllSettings(){
  var a=Map.<String,Object>of("name","세계","fixed_settings","밤에는 마법 금지","confirmed",true);
  var b=Map.<String,Object>of("name","인물","fixed_settings","주인공은 고소공포증","confirmed",true);
  var got=LoreBook.clean(Map.of("books",List.of(a,b),"confirmed",true,"fixed_settings","ignored"),true);
  assertThat((List<?>)got.get("books")).hasSize(2);assertThat(got.get("fixed_settings").toString()).contains("밤에는 마법 금지","주인공은 고소공포증").doesNotContain("ignored");
 }
 @Test void collectionChecksEveryConfirmationAndRejectsNestedBooks(){
  assertThatThrownBy(()->LoreBook.clean(Map.of("books",List.of(Map.of("fixed_settings","draft")),"confirmed",true),true)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->LoreBook.clean(Map.of("books",List.of(Map.of("books",List.of())),"confirmed",true),true)).isInstanceOf(BusinessException.class);
 }
 @Test void collectionHasSharedBudget(){var b=Map.<String,Object>of("name","test","fixed_settings","x".repeat(8000),"confirmed",true);assertThatThrownBy(()->LoreBook.clean(Map.of("books",List.of(b,b),"confirmed",true),true)).isInstanceOf(BusinessException.class);}

}
