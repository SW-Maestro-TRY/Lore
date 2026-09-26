package com.lore.webtoon.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 하네스가 남긴 meta.json 을 원가 장부로 옮긴다 — 웹툰·캐릭터가 같은 길을 쓴다(#444). */
class UsageMetaFileTest {

    @TempDir
    Path dir;

    private final UsageRepository repo = mock(UsageRepository.class);
    private final UsageService service = new UsageService(repo, mock(SpendGuard.class), "t");

    @Test
    @DisplayName("캐릭터 호출(성공·실패)을 char- 이름으로 쌓고, 실패는 0원으로 사유를 남긴다")
    void 캐릭터_호출을_쌓는다() throws Exception {
        Files.writeString(dir.resolve("meta.json"), """
                {"run_id":"abc","calls":[
                  {"stage":"SHEET","provider":"openai","model":"gpt-4.1",
                   "at":"2026-09-26T13:20:32+09:00","seconds":1.2,
                   "usage":{"input":100,"output":50},
                   "cost":{"total":0.0006,"total_krw":1}},
                  {"stage":"SHEET_IMAGE","provider":"openai","model":"gpt-image-2",
                   "usage":null,"stop":null,
                   "cost":{"total":0.0,"total_krw":0},
                   "error":"RuntimeError: moderation_blocked"}
                ]}""");
        when(repo.existsByRunIdAndSeq(anyString(), anyInt())).thenReturn(false);

        int saved = service.ingestMetaFile(UsageService.CHARACTER_PREFIX + "abc", dir.resolve("meta.json"));

        assertThat(saved).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UsageRecord>> got = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(got.capture());
        List<UsageRecord> rows = got.getValue();
        assertThat(rows).extracting(UsageRecord::getRunId).containsOnly("char-abc");
        assertThat(rows).extracting(UsageRecord::getCostKrw).containsExactly(1L, 0L);
        assertThat(rows).extracting(UsageRecord::getStage).containsExactly("SHEET", "SHEET_IMAGE");
        assertThat(rows.get(0).getCalledAt()).isEqualTo(Instant.parse("2026-09-26T04:20:32Z"));
    }

    @Test
    @DisplayName("호출 시각은 하네스가 적은 글(ISO)대로 — 예전처럼 적재 시각으로 바뀌지 않는다")
    void 호출_시각을_읽는다() {
        ObjectMapper m = new ObjectMapper();
        assertThat(UsageService.calledAt(m.valueToTree("2026-09-26T13:20:32+09:00")))
                .isEqualTo(Instant.parse("2026-09-26T04:20:32Z"));
        assertThat(UsageService.calledAt(m.valueToTree(1757300000.0)))
                .isEqualTo(Instant.ofEpochSecond(1757300000L));
        assertThat(UsageService.calledAt(m.valueToTree("엉뚱한 값"))).isNull();
    }

    @Test
    @DisplayName("파일이 없으면 -1 — 죽지 않는다")
    void 파일이_없으면() throws Exception {
        assertThat(service.ingestMetaFile("char-x", dir.resolve("없음.json"))).isEqualTo(-1);
    }
}
