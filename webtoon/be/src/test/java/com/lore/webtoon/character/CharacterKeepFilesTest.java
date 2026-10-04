package com.lore.webtoon.character;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 캐릭터 작업 폴더를 남길지 — 로컬은 남기고 배포 서버는 치운다(#444, #157 과 같은 판단). */
class CharacterKeepFilesTest {

    @Test
    @DisplayName("버킷이 없거나 창고가 이 기계면 로컬 — 남긴다")
    void 로컬은_남긴다() {
        assertThat(CharacterService.keepsFiles("", "", "")).isTrue();
        assertThat(CharacterService.keepsFiles("lore-dev-contents", "http://localhost:9000", null)).isTrue();
        assertThat(CharacterService.keepsFiles("lore-dev-contents", "http://127.0.0.1:9000", "")).isTrue();
    }

    @Test
    @DisplayName("창고가 바깥 주소거나 AWS 기본(주소 없음)이면 배포 — 치운다")
    void 배포는_치운다() {
        assertThat(CharacterService.keepsFiles("lore-prod", "", "")).isFalse();
        assertThat(CharacterService.keepsFiles("lore-dev", "http://minio:9000", "")).isFalse();
    }

    @Test
    @DisplayName("설정이 있으면 그대로 따른다")
    void 설정이_우선() {
        assertThat(CharacterService.keepsFiles("lore-prod", "", "true")).isTrue();
        assertThat(CharacterService.keepsFiles("", "", "false")).isFalse();
    }
}
