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
        when(art.read("private/art.png")).thenReturn(new byte[]{1,2,3});
        when(jobs.save(any())).thenAnswer(i->i.getArgument(0));
        String job=service.create(request(),2L,"uid",null);
        verify(recorder).record(job,c);
        var captor=org.mockito.ArgumentCaptor.forClass(WebtoonJob.class);
        verify(jobs).save(captor.capture());
        assertThat(captor.getValue().getInputJson()).contains("public-character");
        assertThat(java.nio.file.Files.readString(dir.resolve(job).resolve("character.json"))).contains("이름");
    }

}
