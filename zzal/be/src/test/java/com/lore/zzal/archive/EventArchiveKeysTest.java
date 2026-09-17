package com.lore.zzal.archive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 보관 파일이 놓이는 자리 — 공개로 나가는 자리가 아니고, 같은 구간이면 같은 이름이다.
 */
@DisplayName("보관 경로")
class EventArchiveKeysTest {

    @Test
    @DisplayName("★ 한 뿌리 아래 dt= 로 가른다 — 계정을 옮길 때 통째로 복사할 수 있게")
    void oneRootThenDatePartitions() {
        String key = EventArchiveKeys.partKey("archive", LocalDate.of(2026, 9, 14), 1, 5000);

        assertThat(key).isEqualTo("archive/zzal/events/dt=2026-09-14/part-000000000001-000000005000.jsonl.gz");
        assertThat(key).startsWith("archive/");
    }

    @Test
    @DisplayName("★★ 같은 구간이면 같은 이름 — 올리다 죽어도 다시 올릴 때 덮어쓴다")
    void theSameRangeAlwaysGivesTheSameName() {
        LocalDate dt = LocalDate.of(2026, 9, 14);

        assertThat(EventArchiveKeys.partKey("archive", dt, 7, 19))
                .isEqualTo(EventArchiveKeys.partKey("archive", dt, 7, 19));
        assertThat(EventArchiveKeys.partKey("archive", dt, 7, 19))
                .isNotEqualTo(EventArchiveKeys.partKey("archive", dt, 20, 30));
    }

    @Test
    @DisplayName("★ 이름순 정렬이 곧 시간순 — 자릿수를 채운다")
    void zeroPaddedSoNameOrderIsTimeOrder() {
        LocalDate dt = LocalDate.of(2026, 9, 14);

        assertThat(EventArchiveKeys.partKey("archive", dt, 9, 9))
                .isLessThan(EventArchiveKeys.partKey("archive", dt, 10, 10));
    }

    @Test
    @DisplayName("★★ images/ 아래에는 못 놓는다 — CloudFront 가 공개로 내보내는 자리다")
    void refusesThePubliclyServedPrefix() {
        assertThat(EventArchiveKeys.prefixProblem("images")).contains("공개로 나가는 자리");
        assertThat(EventArchiveKeys.prefixProblem("images/zzal")).contains("공개로 나가는 자리");

        assertThatThrownBy(() -> EventArchiveKeys.partKey("images", LocalDate.of(2026, 9, 14), 1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공개로 나가는 자리");
    }

    @Test
    @DisplayName("빈 뿌리·앞뒤 슬래시는 막는다 — 키가 // 로 시작하면 찾을 수도 지울 수도 없다")
    void refusesEmptyOrSlashedPrefixes() {
        assertThat(EventArchiveKeys.prefixProblem("")).isNotNull();
        assertThat(EventArchiveKeys.prefixProblem(null)).isNotNull();
        assertThat(EventArchiveKeys.prefixProblem("/archive")).isNotNull();
        assertThat(EventArchiveKeys.prefixProblem("archive/")).isNotNull();

        assertThat(EventArchiveKeys.prefixProblem("archive")).isNull();
        assertThat(EventArchiveKeys.prefixProblem("lore-archive/v1")).isNull();
    }
}
