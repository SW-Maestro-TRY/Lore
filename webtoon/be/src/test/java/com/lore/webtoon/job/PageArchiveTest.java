package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 검수로 다시 그리기 전에 이전 그림을 지난 판으로 남기는가(#514). */
class PageArchiveTest {

    @Test
    @DisplayName("다시 그릴 때마다 v1, v2 … 로 쌓이고 다른 장 번호와 안 섞인다")
    void 쌓인다(@TempDir Path pages) throws Exception {
        Path four = pages.resolve("page04.png");
        Files.writeString(four, "원본");
        Files.createDirectories(pages.resolve("versions"));
        Files.writeString(pages.resolve("versions").resolve("page14.v7.png"), "다른 장");

        assertThat(JobRunner.archive(four, 4).getFileName().toString()).isEqualTo("page04.v1.png");
        Files.writeString(four, "첫 다시 그림");
        assertThat(JobRunner.archive(four, 4).getFileName().toString()).isEqualTo("page04.v2.png");
        assertThat(Files.readString(pages.resolve("versions").resolve("page04.v1.png"))).isEqualTo("원본");
    }

    @Test
    @DisplayName("그림이 없으면 아무것도 안 남긴다")
    void 없으면() throws Exception {
        assertThat(JobRunner.archive(Path.of("/없는/page03.png"), 3)).isNull();
    }
}
