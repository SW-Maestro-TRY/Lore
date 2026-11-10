package com.lore.zzal.attendance;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.persona.AuthoredPersona;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PublishedBackgroundCatalogTest {
 static String e(String key,String world,String tag,boolean published,String review){return "{\"key\":\"images/"+key+".webp\",\"url\":\"/images/"+key+".webp\",\"world\":\""+world+"\",\"tags\":[\""+tag+"\"],\"published\":"+published+",\"autoCheck\":\"PASS\",\"humanReview\":\""+review+"\"}";}
 @Test void absentAndSkippedPersonaUsesOnlyModernDaily(){
  var pet=mock(ZzalPet.class);var c=new PublishedBackgroundCatalog("["+e("fantasy","fantasy","마법",true,"UNREVIEWED")+","+e("modern","modern_daily","집",true,"UNREVIEWED")+"]");
  assertThat(c.select(pet).orElseThrow().key()).isEqualTo("images/modern.webp");
  when(pet.getAuthoredPersona()).thenReturn(new AuthoredPersona(1,List.of(new AuthoredPersona.Field("world",List.of("마법"),"마법",true))));
  assertThat(c.select(pet).orElseThrow().key()).isEqualTo("images/modern.webp");
  assertThat(new PublishedBackgroundCatalog("["+e("fantasy","fantasy","마법",true,"UNREVIEWED")+"]").select(pet)).isEmpty();
 }
 @Test void highestMetadataFitWinsWithoutConfidenceCutoffAndStableTie(){
  var pet=mock(ZzalPet.class);when(pet.getAuthoredPersona()).thenReturn(new AuthoredPersona(1,List.of(new AuthoredPersona.Field("world",List.of(),"마법 숲",false))));
  var c=new PublishedBackgroundCatalog("["+e("b","fantasy","마법",true,"UNREVIEWED")+","+e("a","fantasy","숲",true,"UNREVIEWED")+","+e("modern","modern_daily","집",true,"UNREVIEWED")+"]");
  assertThat(c.select(pet).orElseThrow().key()).isEqualTo("images/a.webp");
 }
 @Test void legacyWrittenSourceIsUsedWhenAuthoredSourceIsAbsent(){
  var pet=mock(ZzalPet.class);when(pet.getNote()).thenReturn("마법 숲의 작은 집");
  var c=new PublishedBackgroundCatalog("["+e("fantasy","fantasy","마법",true,"UNREVIEWED")+","+e("modern","modern_daily","도시",true,"UNREVIEWED")+"]");
  assertThat(c.select(pet).orElseThrow().key()).isEqualTo("images/fantasy.webp");
  when(pet.getAuthoredPersona()).thenReturn(new AuthoredPersona(1,List.of(new AuthoredPersona.Field("world",List.of(),"",true))));
  assertThat(c.select(pet).orElseThrow().key()).as("explicit skipped authored source does not resurrect legacy settings").isEqualTo("images/modern.webp");
 }
 @Test void unpublishedRejectedAndMissingReviewNeverSelected(){
  var pet=mock(ZzalPet.class);assertThat(new PublishedBackgroundCatalog("["+e("x","modern_daily","",false,"UNREVIEWED")+","+e("y","modern_daily","",true,"REJECTED")+","+e("z","modern_daily","",true,"UNKNOWN")+"]").select(pet)).isEmpty();
 }
}
