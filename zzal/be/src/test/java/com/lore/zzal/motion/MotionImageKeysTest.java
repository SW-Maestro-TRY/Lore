package com.lore.zzal.motion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 그림 주소 규약 — <b>판 번호가 주소에 들어간다.</b>
 *
 * <h3>★★ 이 시험이 지키는 것</h3>
 * 판이 없으면 다시 구운 그림이 같은 주소로 올라간다. 업로드도 DB 도 성공하는데 CDN 1년 캐시가
 * <b>옛 그림을 계속 내보낸다</b> — 서버에는 아무 오류가 없어 화면을 봐야만 드러난다.
 * 그래서 "주소에 판이 있는가" 를 값으로 못 박는다.
 */
@DisplayName("그림 주소 — 판 번호와 한 자리 조립")
class MotionImageKeysTest {

    @Test
    @DisplayName("★ 기본 행동 주소에 판이 들어간다 — 다시 구우면 새 주소다")
    void basicCarriesTheRound() {
        assertThat(MotionImageKeys.basic(7L, 1, "base"))
                .isEqualTo("images/zzal/pets/7/basic/1/base.webp");
        assertThat(MotionImageKeys.basic(7L, 2, "base"))
                .as("판이 오르면 주소가 달라져야 덮어쓰기가 사라진다")
                .isNotEqualTo(MotionImageKeys.basic(7L, 1, "base"));
    }

    @Test
    @DisplayName("★ 앵커는 그 판의 그림과 같은 자리 — 판이 갈리면 다른 판의 그림을 설명하게 된다")
    void anchorsSitNextToThatRoundsImages() {
        assertThat(MotionImageKeys.anchors(7L, 3))
                .isEqualTo("images/zzal/pets/7/basic/3/anchors.json")
                .startsWith(MotionImageKeys.basicPrefix(7L, 3));
    }

    @Test
    @DisplayName("★ 판이 없는 옛 펫 — 터지지 않고 옛 주소를 그대로 준다")
    void roundZeroFallsBackToTheOldShape() {
        // 0 은 "판 번호 이전" 이다. .../basic/0/base.webp 를 주면 있지도 않은 주소라 화면이 빈 그림을 그린다.
        assertThat(MotionImageKeys.basic(7L, 0, "base")).isEqualTo("images/zzal/pets/7/basic/base.webp");
        assertThat(MotionImageKeys.advanced(7L, 9L, 0)).isEqualTo("images/zzal/pets/7/motions/9/motion.webp");
    }

    @Test
    @DisplayName("★ 심화 주소에도 판이 들어간다 — 다음 밤이 어제 그림을 덮어쓰지 않는다")
    void advancedCarriesTheRound() {
        assertThat(MotionImageKeys.advanced(7L, 9L, 2))
                .isEqualTo("images/zzal/pets/7/motions/9/2/motion.webp");
        assertThat(MotionImageKeys.advancedPrefix(7L, 9L, 2))
                .as("후처리가 올릴 폴더와 완성본 주소가 같은 자리에서 나와야 한다")
                .isEqualTo("images/zzal/pets/7/motions/9/2");
    }

    @Test
    @DisplayName("★ 기본과 심화는 다른 자리다 — 심화 공유가 기본 그림을 가리키면 남에게 404 다")
    void basicAndAdvancedNeverCollide() {
        assertThat(MotionImageKeys.advanced(7L, 1L, 1))
                .doesNotContain("/basic/");
        assertThat(MotionImageKeys.basic(7L, 1, "roll"))
                .doesNotContain("/motions/");
    }

    @Test
    @DisplayName("앵커가 있는 펫인가 — 내는 버전이고 판이 한 번이라도 올라갔을 때만")
    void anchorsExistOnlyForVersionsThatEmitThem() {
        assertThat(MotionImageKeys.hasAnchors("v4", 1)).isTrue();
        assertThat(MotionImageKeys.hasAnchors("v4", 0)).as("아직 한 판도 안 구웠다").isFalse();
        assertThat(MotionImageKeys.hasAnchors("v2", 1)).as("v2 스크립트는 앵커를 안 만든다").isFalse();
        assertThat(MotionImageKeys.hasAnchors(null, 1)).isFalse();
    }
}
