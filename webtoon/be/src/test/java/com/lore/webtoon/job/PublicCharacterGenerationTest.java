package com.lore.webtoon.job;
import com.lore.common.exception.*;
import com.lore.common.s3.*;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.character.*;
import com.lore.webtoon.push.JobPush;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PublicCharacterGenerationTest {
    @TempDir Path dir;
    WebtoonJobRepository jobs=mock(WebtoonJobRepository.class);
    CharacterService characters=mock(CharacterService.class);
    CharacterOwner owner=mock(CharacterOwner.class);
    JobService service;
    PrivateArt art=mock(PrivateArt.class);
    com.lore.webtoon.community.CharacterUseRecorder recorder=mock(com.lore.webtoon.community.CharacterUseRecorder.class);
    @BeforeEach void setup() {
        service=new JobService(jobs,mock(JobStore.class),mock(JobQueue.class),mock(JobRunner.class),
          mock(JobProgress.class),mock(StoryStore.class),mock(WorkLedger.class),mock(JobNotice.class),
          characters,owner,art,mock(S3Service.class),mock(S3Storage.class),mock(SafetyGuard.class),
          mock(WebtoonCastSheetRepository.class),mock(RunArt.class),mock(JobPush.class),2,2,dir.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"communityUses",recorder);
        when(owner.uidsOf(2L,"uid")).thenReturn(List.of("uid"));
    }
    JobService.CreateRequest request() {
        return new JobService.CreateRequest("이름","설명","일상","이야기","frost",null,null,null,true,true,
                "uid","public-character",null,null,"ko","quick","","","");
    }
    @Test void missingCharacterFailsBeforeJobCreation() {
        when(characters.byPublicId(eq("public-character"),eq(2L),any())).thenThrow(new BusinessException(ErrorCode.NOT_FOUND,"공개 취소"));
        assertThatThrownBy(()->service.create(request(),2L,"uid",null)).isInstanceOf(BusinessException.class);
        verify(jobs,never()).save(any());
    }
    @Test void unavailableArtDoesNotSilentlyGenerateDifferentCharacter() {
        var c=WebtoonCharacter.drawing("public-character",1L,"writer","이름","설명",Instant.now());
        c.drewArt("private/art.png",CharacterSource.PROMPT,Instant.now());c.publishForReuse(true,1L,Instant.now());
        when(characters.byPublicId(eq("public-character"),eq(2L),any())).thenReturn(c);
        assertThatThrownBy(()->service.create(request(),2L,"uid",null)).isInstanceOf(BusinessException.class).hasMessageContaining("캐릭터 그림");
        verify(jobs,never()).save(any());
    }
    @Test void selectedCharacterReachesSavedInputAndProvenanceWithoutModelCall() throws Exception {
        var c=WebtoonCharacter.drawing("public-character",1L,"writer","이름","설명",Instant.now());
        c.drewArt("private/art.png",CharacterSource.PROMPT,Instant.now());c.publishForReuse(true,1L,Instant.now());
        when(characters.byPublicId(eq("public-character"),eq(2L),any())).thenReturn(c);
        when(art.read("private/art.png")).thenReturn(imageBytes("png"));
        when(jobs.save(any())).thenAnswer(i->i.getArgument(0));
        String job=service.create(request(),2L,"uid",null);
        verify(recorder).record(job,c);
        var captor=org.mockito.ArgumentCaptor.forClass(WebtoonJob.class);
        verify(jobs).save(captor.capture());
        assertThat(captor.getValue().getInputJson()).contains("public-character");
        assertThat(java.nio.file.Files.readString(dir.resolve(job).resolve("character.json"))).contains("이름");
    }

    @Test void uploadedOriginalMustNotDisappearDuringGeneration() {
        var c=WebtoonCharacter.drawing("public-character",2L,"uid","이름","설명",Instant.now());
        c.drewArt("private/original.jpg",CharacterSource.UPLOAD,Instant.now());
        when(characters.byPublicId(eq("public-character"),eq(2L),any())).thenReturn(c);
        assertThatThrownBy(()->service.create(request(),2L,"uid",null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("캐릭터 그림");
        verify(jobs,never()).save(any());
    }
    @Test void jpegOriginalGetsRealPngReferenceWithoutChangingStoredOriginal() throws Exception {
        byte[] original=imageBytes("jpeg");
        var c=WebtoonCharacter.drawing("public-character",2L,"uid","이름","설명",Instant.now());
        c.drewArt("private/original.jpg",CharacterSource.UPLOAD,Instant.now());
        when(characters.byPublicId(eq("public-character"),eq(2L),any())).thenReturn(c);
        when(art.read("private/original.jpg")).thenReturn(original);
        when(jobs.save(any())).thenAnswer(i->i.getArgument(0));
        String job=service.create(request(),2L,"uid",null);
        Path ref=dir.resolve(job).resolve("charart.png");
        byte[] bytes=java.nio.file.Files.readAllBytes(ref);
        assertThat(bytes[0]).isEqualTo((byte)137);
        assertThat(java.nio.file.Files.size(ref)).isLessThanOrEqualTo(PhotoFile.MAX_BYTES);
        verify(art,never()).upload(any(),any(),anyBoolean());
        assertThat(c.getArtKey()).isEqualTo("private/original.jpg");
    }
    @Test void replacedImageDoesNotReuseTheOldAiWorldOrCard() throws Exception {
        var c=WebtoonCharacter.drawing("public-character",2L,"uid","이름","새 설정",Instant.now());
        c.drewPanel("private/original.jpg",CharacterSource.REPLACED_UPLOAD,
                new WebtoonCharacter.Card("old-world","장르","판타지","인물","중심","old-card","",
                        List.of(),List.of(),"webtoon",false,"human"),Instant.now());
        when(characters.byPublicId(eq("public-character"),eq(2L),any())).thenReturn(c);
        when(art.read("private/original.jpg")).thenReturn(imageBytes("jpeg"));
        when(jobs.save(any())).thenAnswer(i->i.getArgument(0));
        String job=service.create(request(),2L,"uid",null);
        var input=new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                dir.resolve(job).resolve("character.json").toFile());
        assertThat(input.has("card")).isFalse();
        assertThat(input.path("world").path("preset").asText()).isEmpty();
    }

    @Test void editedCardGoesToHarnessAsEditedWithoutTheOriginalAiText() throws Exception {
        var c=WebtoonCharacter.drawing("public-character",2L,"uid","몽이","",Instant.now());
        c.drewPanel("private/art.png",CharacterSource.PROMPT,
                new WebtoonCharacter.Card("romance_novel","로판","로맨스","악역 영애","곁","원래 AI 반전","몽이: 멍!",
                        List.of(new WebtoonCharacter.DialogueLine("몽이",true,"left","멍!")),
                        List.of("황태자와 춤을 춘다","파혼 소식"),"romance_fantasy",false,"강아지"),Instant.now());
        c.editCard(new WebtoonCharacter.CardEdit(null,null,null,null,"사서","중심","고친 소개",
                List.of("도서관을 지킨다"),List.of()),Instant.now());
        when(characters.byPublicId(eq("public-character"),eq(2L),any())).thenReturn(c);
        when(art.read("private/art.png")).thenReturn(imageBytes("png"));
        when(jobs.save(any())).thenAnswer(i->i.getArgument(0));
        // 화면이 고치기 전에 받아 둔 설정 글(원래 AI 반전이 든)을 보내도 저장된 값이 이긴다
        var form=new JobService.CreateRequest("몽이","로판 · 로맨스\n원래 AI 반전","일상","내 이야기","frost",null,null,null,true,true,
                "uid","public-character",null,null,"ko","quick","","","");
        String job=service.create(form,2L,"uid",null);
        String json=java.nio.file.Files.readString(dir.resolve(job).resolve("character.json"));
        var input=new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        var card=input.path("card");
        assertThat(card.path("twist").asText()).isEqualTo("고친 소개");
        assertThat(card.path("role").asText()).isEqualTo("사서");
        assertThat(card.path("quote").asText()).isEmpty();
        assertThat(card.path("fate")).hasSize(1);
        assertThat(card.path("fate").get(0).asText()).isEqualTo("도서관을 지킨다");
        assertThat(json).doesNotContain("원래 AI 반전").doesNotContain("황태자").doesNotContain("악역 영애");
        assertThat(input.path("character").asText()).contains("고친 소개");
    }

    private byte[] imageBytes(String format) throws Exception {
        var image=new java.awt.image.BufferedImage(20,20,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image,format,out);
        return out.toByteArray();
    }

}
