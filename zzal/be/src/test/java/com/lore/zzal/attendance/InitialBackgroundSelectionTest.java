package com.lore.zzal.attendance;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.persona.AuthoredPersona;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class InitialBackgroundSelectionTest {
 static final Path CATALOG=Path.of("zzal/be/src/test/resources/attendance/review-background-catalog.json");
 ZzalPet source(String world,String extra){var p=mock(ZzalPet.class);when(p.getAuthoredPersona()).thenReturn(new AuthoredPersona(1,List.of(new AuthoredPersona.Field("world",List.of(),world,world.isBlank()),new AuthoredPersona.Field("extra",List.of(),extra,extra.isBlank()))));return p;}
 @Test void realReviewCatalogFiveSourcesStayInTheirSelectedMiddleCategoryAndNeverRepeat()throws Exception{
  var catalog=new PublishedBackgroundCatalog(Files.readString(CATALOG));var cases=new LinkedHashMap<String,ZzalPet>();cases.put("empty",source("",""));cases.put("modern_demon",source("현대 도시","악마 종족"));cases.put("joseon",source("조선 시대 한양 한옥",""));cases.put("fantasy",source("판타지 마계 계약 상점",""));cases.put("space",source("우주선 항해",""));
  var expected=Map.of("empty","modern_daily::주거·일상","modern_demon","modern_daily::도심·번화가","joseon","역사·시대물::동아시아 전통","fantasy","판타지::마계·악마","space","미래·SF::우주 생활·항해");var results=new ArrayList<Map<String,Object>>();
  for(var item:cases.entrySet()){var initial=catalog.select(item.getValue()).orElseThrow();var cluster=PublishedBackgroundCatalog.clusterKey(initial);assertThat(cluster).as(item.getKey()).isEqualTo(expected.get(item.getKey()));var next=catalog.select(item.getValue(),initial.key(),cluster);if(item.getKey().equals("joseon")){assertThat(next).as("Japanese riverside cannot satisfy explicit Joseon/Korean context").isEmpty();}else{var day2=next.orElseThrow();assertThat(day2.key()).isNotEqualTo(initial.key());assertThat(PublishedBackgroundCatalog.clusterKey(day2)).isEqualTo(cluster);}var result=new LinkedHashMap<String,Object>();result.put("case",item.getKey());result.put("initial",initial);result.put("cluster",cluster);result.put("day2",next.orElse(null));result.put("status",next.isPresent()?"AVAILABLE":"PENDING_NO_COMPATIBLE_ASSET");results.add(result);}
  assertThat(catalog.select(cases.get("empty")).orElseThrow().key()).endsWith("03.png");
  Path out=Path.of("build/test-audit/initial-background/selection-five.json");Files.createDirectories(out.getParent());Files.writeString(out,new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
 }
 @Test void singletonMiddleCategoryRemainsPendingInsteadOfJumpingWorlds()throws Exception{
  var c=new PublishedBackgroundCatalog(Files.readString(CATALOG));var p=source("판타지 모험가 길드 여관","");var first=c.select(p).orElseThrow();assertThat(first.key()).endsWith("22.png");assertThat(c.select(p,first.key(),PublishedBackgroundCatalog.clusterKey(first))).isEmpty();
 }
}
