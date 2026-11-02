package com.lore.webtoon.job;
import com.lore.common.exception.*;
import com.lore.common.s3.*;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.character.*;
import com.lore.webtoon.push.JobPush;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 주인공 여러 명(#899) — 함께 주인공으로 고른 캐릭터가 권한 확인을 거쳐 하네스 입력의 co 로 가는지. 모델 호출 없음. */
class CoProtagonistCreateTest {
    @TempDir Path dir;
    WebtoonJobRepository jobs=mock(WebtoonJobRepository.class);
    CharacterService characters=mock(CharacterService.class);
    CharacterOwner owner=mock(CharacterOwner.class);
    PrivateArt art=mock(PrivateArt.class);
    JobService service;
    @BeforeEach void setup() throws Exception {
        service=new JobService(jobs,mock(JobStore.class),mock(JobQueue.class),mock(JobRunner.class),
          mock(JobProgress.class),mock(StoryStore.class),mock(WorkLedger.class),mock(JobNotice.class),
          characters,owner,art,mock(S3Service.class),mock(S3Storage.class),mock(SafetyGuard.class),
          mock(WebtoonCastSheetRepository.class),mock(RunArt.class),mock(JobPush.class),2,2,dir.toString());
        when(owner.uidsOf(2L,"uid")).thenReturn(List.of("uid"));
        when(jobs.save(any())).thenAnswer(i->i.getArgument(0));
        when(art.read(any())).thenReturn(png());
        when(characters.byPublicId(eq("first"),eq(2L),any())).thenReturn(mine("first","하윤","정비공"));
        when(characters.byPublicId(eq("second"),eq(2L),any())).thenReturn(mine("second","서진","우편배달부"));
    }
    WebtoonCharacter mine(String id,String name,String desc) {
        var c=WebtoonCharacter.drawing(id,2L,"uid",name,desc,Instant.now());
        c.drewArt("private/"+id+".png",CharacterSource.PROMPT,Instant.now());
        return c;
    }
    JobService.CreateRequest request(List<String> co) {
        return new JobService.CreateRequest("하윤","정비공","","","frost",null,null,null,true,true,
                "uid","first",null,null,"ko","quick","","","",null,co);
    }

    @Test void secondCharacterGoesToHarnessWithItsOwnArt() throws Exception {
        String job=service.create(request(List.of("second")),2L,"uid",null);
        var input=new ObjectMapper().readTree(dir.resolve(job).resolve("character.json").toFile());
        assertThat(input.path("co")).hasSize(1);
        var co=input.path("co").get(0);
        assertThat(co.path("name").asText()).isEqualTo("서진");
        assertThat(co.path("character").asText()).contains("우편배달부");
        assertThat(co.path("photo").asText()).endsWith("charart-co1.png");
        assertThat(Files.exists(Path.of(co.path("photo").asText()))).isTrue();
        // 첫 주인공 사진 목록에는 두 번째 사람의 그림이 섞이지 않는다
        assertThat(input.path("photo").asText()).endsWith("charart.png");
        var captor=org.mockito.ArgumentCaptor.forClass(WebtoonJob.class);
        verify(jobs).save(captor.capture());
        var saved=new ObjectMapper().readTree(captor.getValue().getInputJson());
        assertThat(saved.path("co_character_ids").get(0).asText()).isEqualTo("second");
        assertThat(saved.path("co_names").get(0).asText()).isEqualTo("서진");
        verify(characters).byPublicId(eq("second"),eq(2L),any());
    }

    @Test void oneCharacterRunHasNoCo() throws Exception {
        String job=service.create(request(null),2L,"uid",null);
        var input=new ObjectMapper().readTree(dir.resolve(job).resolve("character.json").toFile());
        assertThat(input.has("co")).isFalse();
        var captor=org.mockito.ArgumentCaptor.forClass(WebtoonJob.class);
        verify(jobs).save(captor.capture());
        assertThat(captor.getValue().getInputJson()).doesNotContain("co_names");
    }

    @Test void upToFourProtagonistsGoToHarness() throws Exception {
        when(characters.byPublicId(eq("third"),eq(2L),any())).thenReturn(mine("third","도경","검사"));
        when(characters.byPublicId(eq("fourth"),eq(2L),any())).thenReturn(mine("fourth","리안","기사"));
        String job=service.create(request(List.of("second","third","fourth")),2L,"uid",null);
        var input=new ObjectMapper().readTree(dir.resolve(job).resolve("character.json").toFile());
        assertThat(input.path("co")).hasSize(3);
        assertThat(input.path("co").get(2).path("photo").asText()).endsWith("charart-co3.png");
    }

    @Test void guestsCannotAddProtagonists() {
        assertThatThrownBy(()->service.create(request(List.of("second")),null,"uid",null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("로그인");
        verify(jobs,never()).save(any());
    }

    @Test void moreThanFourOrDuplicatesAreRejectedBeforeSaving() {
        assertThatThrownBy(()->service.create(request(List.of("second","third","fourth","fifth")),2L,"uid",null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("4명까지");
        assertThatThrownBy(()->service.create(request(List.of("first")),2L,"uid",null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("두 번");
        verify(jobs,never()).save(any());
    }

    @Test void someoneElsesCharacterIsRejected() {
        when(characters.byPublicId(eq("other"),eq(2L),any())).thenThrow(new BusinessException(ErrorCode.NOT_FOUND,"없음"));
        assertThatThrownBy(()->service.create(request(List.of("other")),2L,"uid",null)).isInstanceOf(BusinessException.class);
        verify(jobs,never()).save(any());
    }

    @Test void coNeedsAPickedFirstCharacter() {
        var form=new JobService.CreateRequest("하윤","정비공","","","frost",null,null,null,true,true,
                "uid",null,null,null,"ko","quick","","","",null,List.of("second"));
        assertThatThrownBy(()->service.create(form,2L,"uid",null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("첫 주인공");
    }

    @Test void requestReadsCoCharacterIds() throws Exception {
        var form=new ObjectMapper().readValue("{\"name\":\"a\",\"character_id\":\"x\",\"co_character_ids\":[\"y\"]}",
                JobService.CreateRequest.class);
        assertThat(form.coCharacterIds()).containsExactly("y");
    }

    @Test void coNamesAreReadFromSavedInput() {
        assertThat(JobService.coNamesOf("{\"co_names\":[\"서진\"]}",new ObjectMapper())).containsExactly("서진");
        assertThat(JobService.coNamesOf("{\"name\":\"하윤\"}",new ObjectMapper())).isEmpty();
    }

    private byte[] png() throws Exception {
        var image=new java.awt.image.BufferedImage(20,20,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image,"png",out);
        return out.toByteArray();
    }
}
