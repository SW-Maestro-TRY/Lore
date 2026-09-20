package com.lore.zzal.config;

import com.lore.zzal.it.ZzalIntegrationTest;
import com.lore.zzal.it.ZzalItSupport;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시나리오 10 — <b>부팅 때 DB 를 고치는 코드</b>가 실제 Postgres 에서 도는가(M-21).
 *
 * <h3>★ 왜 이 코드가 있나</h3>
 * Hibernate 는 {@code @Enumerated(STRING)} 칸마다 <b>허용 값 목록 CHECK 제약</b>을 만든다.
 * enum 에 값을 더해도 {@code ddl-auto: update} 는 그 제약을 안 고친다 —
 * <b>컴파일·시험·배포·기동이 전부 통과한 채 그 값을 처음 쓰는 INSERT 만</b> 터진다
 * (2026-09-05 부화 완료 때 18행 INSERT 가 실제로 그랬다).
 *
 * <h3>★ 참조 시험이 0건이었다</h3>
 * "빌드·배포·기동이 전부 통과한 채 실제 호출에서만 터진다" 는 종류를 막으라고 만든 코드인데,
 * 정작 <b>그 코드가 도는지</b>를 아무도 안 봤다. 여기서는 진짜 제약을 심어 놓고 지워지는지 본다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 10 — 부팅 스키마 보정")
class SchemaPatchIT extends ZzalItSupport {

    @Autowired ZzalSchemaPatch patch;

    private static final String TABLE = "zzal_motion";

    private void plant(String name, String expression) {
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT %s CHECK (%s)".formatted(TABLE, name, expression));
    }

    @Test
    @DisplayName("★★ 심어 둔 CHECK 제약이 전부 지워진다 — 하나만 지우면 다음 enum 확장 때 같은 사고가 난다")
    void everyCheckConstraintOnTheTableIsDropped() {
        plant("zzal_it_status_check", "status is not null");
        plant("zzal_it_source_check", "source is null or source <> 'NOPE'");
        assertThat(patch.checkConstraints(TABLE))
                .as("심은 것이 보여야 이 시험이 뜻을 갖는다")
                .contains("zzal_it_status_check", "zzal_it_source_check");

        patch.apply();

        assertThat(patch.checkConstraints(TABLE))
                .as("이름을 박지 않고 그 표의 CHECK 를 전부 지운다")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 두 번 돌려도 안전하다 — 부팅마다 도는 코드다")
    void runningTwiceIsSafe() {
        plant("zzal_it_twice_check", "seq >= 0");

        patch.apply();
        assertThatCode(patch::apply).doesNotThrowAnyException();

        assertThat(patch.checkConstraints(TABLE)).isEmpty();
    }

    @Test
    @DisplayName("★ 제약이 하나도 없어도 조용히 지나간다 — 정상 상태에서 부팅을 막으면 안 된다")
    void noConstraintsIsFine() {
        patch.apply();
        assertThatCode(patch::apply).doesNotThrowAnyException();
        assertThat(patch.checkConstraints(TABLE)).isEmpty();
    }

    @Test
    @DisplayName("★★ 없는 표를 물으면 <b>조용히 넘어가지 않고</b> 터진다 — 오타가 조용히 묻히면 지켜지지 않는다")
    void askingAboutAMissingTableFails() {
        assertThatThrownBy(() -> patch.checkConstraints("zzal_no_such_table"))
                .as("표 이름이 틀린 채로 '제약 없음' 이라고 답하면 아무도 모른다")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("지키는 표 목록 — 지금은 동작 표 하나뿐이다(이 줄이 바뀌면 그건 결정이다)")
    void theGuardedTableList() {
        assertThat(ZzalSchemaPatch.ENUM_TABLES).containsExactly(TABLE);
    }

    @Test
    @Disabled("결함 — ENUM_TABLES 가 @Enumerated 를 쓰는 일곱 표 중 하나만 지킨다 (P-7)")
    @DisplayName("★★ @Enumerated 를 쓰는 표는 전부 지켜야 한다 — 새 enum 값을 더하는 날 운영에서만 터진다")
    void everyEnumTableShouldBeGuarded() {
        // 부팅 뒤에도 CHECK 제약이 남아 있는 표가 있으면, 그 표의 enum 에 값을 더하는 날
        // 그 값을 처음 쓰는 INSERT 만 터진다. 2026-09-09 회원가입 500 과 같은 모양이다.
        List<String> enumTables = List.of(
                "zzal_motion", "zzal_motion_candidate", "zzal_pet", "zzal_game",
                "zzal_chat_call", "zzal_gen_job", "zzal_gen_step");

        patch.apply();

        for (String table : enumTables) {
            assertThat(patch.checkConstraints(table)).as("%s 의 CHECK 제약", table).isEmpty();
        }
    }
}
