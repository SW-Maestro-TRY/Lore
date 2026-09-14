package com.lore.zzal.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("접속자 주소 — X-Forwarded-For 에서 누구를 고르나")
class ClientIpTest {

    /** 운영 구성: CloudFront → ALB → 서버. 진짜 접속자 뒤에 우리 설비가 한 칸(ALB) 붙는다. */
    private final ClientIp production = new ClientIp(1);

    @Test
    @DisplayName("★★ 맨 앞은 브라우저가 적어 보낸 값이라 안 쓴다 — 헤더 한 줄로 상한을 우회할 수 있다")
    void theFirstEntryIsNotTrusted() {
        String spoofed = "9.9.9.9, 203.0.113.7, 70.132.1.1";
        assertThat(production.fromHeader(spoofed))
                .as("맨 앞(9.9.9.9)을 쓰면 매번 다른 값을 적어 보내며 무한히 만들 수 있다")
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("★ 사용자가 헤더를 안 보냈으면 CloudFront 가 적은 것이 맨 앞이다")
    void withoutASpoofedEntry() {
        assertThat(production.fromHeader("203.0.113.7, 70.132.1.1")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("공백·빈 값은 없는 것으로 본다 — 없으면 IP 상한만 넘어간다")
    void blankIsNothing() {
        assertThat(production.fromHeader(null)).isNull();
        assertThat(production.fromHeader("   ")).isNull();
        assertThat(production.fromHeader(",")).isNull();
    }

    @Test
    @DisplayName("★ 터무니없이 긴 값은 버린다 — 헤더 길이는 사용자가 정하고 그 문자열이 메모리의 열쇠가 된다")
    void absurdlyLongIsDropped() {
        assertThat(production.fromHeader("x".repeat(200) + ", 70.132.1.1")).isNull();
    }

    @Nested
    @DisplayName("설비가 바뀌면 숫자만 바꾼다")
    class OtherTopologies {

        @Test
        @DisplayName("앞에 아무것도 없으면(0) 맨 뒤가 접속자다")
        void noTrustedHop() {
            assertThat(new ClientIp(0).fromHeader("9.9.9.9, 203.0.113.7")).isEqualTo("203.0.113.7");
        }

        @Test
        @DisplayName("★ 줄이 설비 수보다 짧으면 맨 앞을 쓴다 — 과하게 막는 것보다 덜 막는 쪽을 고른다")
        void shorterThanExpected() {
            // 설정이 설비와 안 맞는 날, 맨 뒤(우리 설비)를 쓰면 <b>세상 모든 사람이 한 칸</b>에
            // 들어가 서비스가 통째로 막힌다. IP 상한은 완화 장치이고 진짜 방벽은 사람당·전체
            // 상한이라, 틀렸을 때 덜 막히는 쪽이 낫다.
            assertThat(new ClientIp(3).fromHeader("203.0.113.7")).isEqualTo("203.0.113.7");
            assertThat(new ClientIp(3).fromHeader("9.9.9.9, 70.132.1.1")).isEqualTo("9.9.9.9");
        }
    }
}
