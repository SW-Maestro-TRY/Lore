package com.lore.zzal.docs;

import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.pet.dto.PetResponses;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 API 문서가 코드보다 낡지 않게.
 *
 * <h3>★★ 왜 시험으로 막나</h3>
 * 설명이 틀려도 아무것도 안 터진다. 서버는 옳게 도는데 <b>그 문서로 만든 화면</b>이 틀린다 —
 * "이름을 넣어야 격자가 돈다" 로 읽은 화면은 이름 입력 전에 아무것도 안 하고 기다리고,
 * "자유 메모가 그림에 들어간다" 로 읽은 사용자는 외형을 적고는 그림이 그대로인 것을 두고
 * <b>생성이 고장났다</b>고 여긴다.
 *
 * <p>여기서 묶는 것은 <b>코드가 이미 답을 아는</b> 셋뿐이다. 문장 전체를 검사하지는 않는다 —
 * 그러면 문서를 다듬을 때마다 시험이 깨져서, 시험이 사실이 아니라 표현을 지키게 된다.
 */
@DisplayName("API 설명 — 코드와 어긋나지 않는다")
class ApiDescriptionsMatchTheCodeTest {

    @Test
    @DisplayName("★ 부화는 그림을 올리는 순간 전부 시작한다 — '이름을 넣어야 격자가 돈다' 로 읽히면 안 된다")
    void hatchStartsWhenTheImageIsUploaded() throws IOException {
        String controller = source("zzal/be/src/main/java/com/lore/zzal/pet/PetController.java");

        // 실제 배선 — draft() 가 부화를 띄우고, character() 는 이름만 채운다.
        String service = source("zzal/be/src/main/java/com/lore/zzal/pet/PetService.java");
        assertThat(service).contains("events.publishEvent(new PetHatchRequested(");

        assertThat(controller)
                .as("시트만 시작한다고 적으면 화면이 나머지를 이름 뒤로 미룬다")
                .doesNotContain("캐릭터 시트 생성을 시작한다")
                .as("격자는 이름과 함께 시작하지 않는다")
                .doesNotContain("격자 생성을 시작한다");
    }

    @Test
    @DisplayName("★ 자유 메모는 그림에 안 들어간다 — 정체성 문단은 등록한 그림에서만 뽑는다")
    void theFreeNoteDoesNotReachTheImage() throws IOException {
        String identity = source("zzal/be/src/main/java/com/lore/zzal/generation/steps/IdentityStep.java");
        // 코드가 답을 안다 — 문단을 만드는 자리가 note 를 재료로 쓰지 않는다.
        assertThat(identity).doesNotContain("ctx.note()");

        assertThat(source("zzal/be/src/main/java/com/lore/zzal/pet/PetController.java"))
                .doesNotContain("그림 생성에 반영되는 입력은 note");
        assertThat(source("zzal/be/src/main/java/com/lore/zzal/pet/dto/PetRequests.java"))
                .doesNotContain("그림 생성에 들어가는 것은 {@code note} 뿐");
    }

    @Test
    @DisplayName("★ 예상 시간 예시가 실제 값과 같다 — 화면이 예시를 그대로 쓰면 2배 길게 기다린다")
    void theEstimateExampleMatchesTheRule() throws NoSuchFieldException {
        // ★ @Schema 는 RECORD_COMPONENT 를 대상으로 두지 않아 record 부품이 아니라 <b>필드</b>에 붙는다.
        Schema schema = PetResponses.Created.class.getDeclaredField("estimatedSeconds").getAnnotation(Schema.class);

        assertThat(schema.example())
                .isEqualTo(String.valueOf(ZzalRules.HATCH_ESTIMATE.toSeconds()));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
    }

    /** 시험은 레포 어디서 돌든 루트를 찾아야 한다. */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("레포 루트를 못 찾았습니다");
        }
        return p;
    }
}
