package com.lore.webtoon.character;

import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.credit.CreditGate;
import com.lore.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

class CharacterOriginalTest {
 @TempDir Path dir;
 final WebtoonCharacterRepository repo=mock(WebtoonCharacterRepository.class);
 final CharacterMaker maker=mock(CharacterMaker.class);
 final PrivateArt art=mock(PrivateArt.class);
 final CreditGate credit=mock(CreditGate.class);
 CharacterService service;
 static final String PNG=png();
 private static String png(){try{var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",out);return "data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray());}catch(Exception e){throw new RuntimeException(e);}}

 @BeforeEach void setup(){
  service=new CharacterService(repo,maker,mock(CharacterOwner.class),art,credit,mock(ShareReward.class),dir.toString(),3,2,false,Clock.systemUTC());
  when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  when(art.upload(any(),eq("image/png"),eq(false))).thenReturn("private/webtoon/char/original.png");
 }
 @Test void original_keeps_image_and_settings_without_ai_or_credits(){
  var c=service.importOriginal(42L,"browser","로젤","공작가의 마지막 후계자",List.of(PNG),List.of(),"guest");
  assertThat(c.getStatus()).isEqualTo(CharacterStatus.READY);
  assertThat(c.getSource()).isEqualTo(CharacterSource.UPLOAD);
  assertThat(c.getDescription()).isEqualTo("공작가의 마지막 후계자");
  assertThat(c.ownedBy(42L)).isTrue();
  verify(art).upload(aryEq(Base64.getDecoder().decode(PNG.split(",")[1])),eq("image/png"),eq(false));
  verifyNoInteractions(maker,credit);
 }
 @Test void guest_original_is_not_builtin(){
  var c=service.importOriginal(null,"browser","로젤","설정",List.of(PNG),List.of(),"guest");
  assertThat(c.isBuiltin()).isFalse();assertThat(c.madeBy(null,List.of("browser"))).isTrue();
  assertThatThrownBy(()->service.importOriginal(null,"","로젤","",List.of(PNG),List.of(),"guest")).isInstanceOf(BusinessException.class);
 }
 @Test void rejects_non_image_without_writing(){
  assertThatThrownBy(()->service.importOriginal(42L,"b","로젤","",List.of("data:image/png;base64,SGVsbG8="),List.of(),"g")).isInstanceOf(BusinessException.class);
  verify(repo,never()).save(any());verifyNoInteractions(art,maker,credit);
 }
 @Test void failed_storage_does_not_report_ready(){
  when(art.upload(any(),anyString(),eq(false))).thenReturn(null);
  assertThatThrownBy(()->service.importOriginal(42L,"b","로젤","",List.of(PNG),List.of(),"g")).isInstanceOf(BusinessException.class);
  verify(repo,never()).save(any());
 }
 @Test void update_checks_owner_before_image_storage(){
  var c=WebtoonCharacter.drawing("id",7L,null,"남의 캐릭터","",Instant.now());c.drewArt("old",CharacterSource.PROMPT,Instant.now());when(repo.findByPublicId("id")).thenReturn(Optional.of(c));
  assertThatThrownBy(()->service.updateOriginal("id",42L,List.of(),"새 이름","",List.of(PNG),List.of(),"g")).isInstanceOf(BusinessException.class);
  verifyNoInteractions(art);
 }
 @Test void update_preserves_creation_source_for_daily_generation_count(){
  var c=WebtoonCharacter.drawing("id",42L,null,"로젤","old",Instant.now());c.drewPanel("old",CharacterSource.PROMPT,new WebtoonCharacter.Card("w","장르","판타지","인물","중심","카드 제목","",List.of(),List.of(),"webtoon",false,"human"),Instant.now());c.publishForReuse(true,42L,Instant.now());when(repo.findByPublicId("id")).thenReturn(Optional.of(c));
  var result=service.updateOriginal("id",42L,List.of(),"로젤","new",List.of(PNG),List.of(),"g");
  assertThat(result.getSource()).isEqualTo(CharacterSource.REPLACED_UPLOAD);assertThat(result.getArtKey()).isNotEqualTo("old");assertThat(result.isCommunityPublic()).isFalse();verifyNoInteractions(maker,credit);
  assertThatThrownBy(()->service.sharedCard("id")).isInstanceOf(BusinessException.class);
 }
}
