package com.lore.zzal.archive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 돌아도 되는가 — 스위치·버킷·뿌리 셋을 각각 따로 막는다.
 *
 * <h3>★ 왜 하나라도 걸리면 안 도나</h3>
 * 이 배치가 잘못 돌면 그 결과가 <b>공개 버킷에 올라간 개인 식별자</b>다. 되돌릴 수 없고,
 * 배포·기동·빌드는 전부 통과한 채 새벽에 조용히 일어난다.
 */
@DisplayName("보관 설정 — 하나라도 어긋나면 안 돈다")
class EventArchiveSettingsTest {

    private static EventArchiveSettings settings(boolean enabled, String bucket, String contentBucket, String prefix) {
        return new EventArchiveSettings(enabled, bucket, contentBucket, prefix, 50000, 5000, 10);
    }

    @Test
    @DisplayName("★ 기본은 꺼짐 — 버킷이 준비되기 전에 켜지면 안 된다")
    void offByDefault() {
        EventArchiveSettings off = settings(false, "lore-archive", "lore-content", "archive");

        assertThat(off.canRun()).isFalse();
        assertThat(off.blockedReason()).contains("app.zzal.archive.enabled");
    }

    @Test
    @DisplayName("★★ 버킷이 비면 안 돈다 — 켜도 안 돈다")
    void emptyBucketStopsIt() {
        EventArchiveSettings blank = settings(true, "", "lore-content", "archive");

        assertThat(blank.canRun()).isFalse();
        assertThat(blank.blockedReason())
                .contains("app.zzal.archive.bucket")
                .contains("ZZAL_ARCHIVE_BUCKET");
    }

    @Test
    @DisplayName("★★ 그림 버킷과 같으면 안 돈다 — 그 버킷은 CloudFront 가 공개로 내보낸다")
    void refusesTheContentBucket() {
        EventArchiveSettings same = settings(true, "lore-content", "lore-content", "archive");

        assertThat(same.canRun()).isFalse();
        assertThat(same.blockedReason()).contains("공개로 내보내는 곳");
    }

    @Test
    @DisplayName("★ 뿌리가 images 면 안 돈다 — 버킷을 나눠도 접두사를 잘못 적으면 같은 사고다")
    void refusesThePublicPrefix() {
        EventArchiveSettings publicPrefix = settings(true, "lore-archive", "lore-content", "images");

        assertThat(publicPrefix.canRun()).isFalse();
        assertThat(publicPrefix.blockedReason()).contains("공개로 나가는 자리");
    }

    @Test
    @DisplayName("상한이 0 이하면 안 돈다 — 0 이면 영원히 아무것도 안 올라가는데 아무 소리도 안 난다")
    void refusesNonPositiveCaps() {
        assertThat(new EventArchiveSettings(true, "b", "c", "archive", 0, 5000, 10).blockedReason())
                .contains("max-rows");
        assertThat(new EventArchiveSettings(true, "b", "c", "archive", 10, 0, 10).blockedReason())
                .contains("chunk");
    }

    @Test
    @DisplayName("다 맞으면 돈다 — 그리고 굳힘 시간은 기본 10분")
    void runsWhenEverythingIsSet() {
        EventArchiveSettings ok = settings(true, "lore-archive", "lore-content", "archive");

        assertThat(ok.canRun()).isTrue();
        assertThat(ok.blockedReason()).isNull();
        assertThat(ok.settleLag().toMinutes()).isEqualTo(10);
    }
}
