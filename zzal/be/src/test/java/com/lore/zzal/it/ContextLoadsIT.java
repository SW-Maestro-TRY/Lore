package com.lore.zzal.it;

import com.lore.zzal.dev.DevClockController;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.PetHatchListener;
import com.lore.zzal.generation.PipelineRegistry;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.client.FakeImageClient;
import com.lore.zzal.generation.client.FakePostProcessor;
import com.lore.zzal.generation.client.FakeTextClient;
import com.lore.zzal.generation.client.ImageClient;
import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.generation.client.TextClient;
import com.lore.zzal.night.BakeTrigger;
import com.lore.zzal.night.NightSweep;
import com.lore.zzal.night.PieceCompletedListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 1 — <b>진짜 컨텍스트가 뜬다.</b>
 *
 * <h3>이것 하나로 무엇이 잡히나</h3>
 * 이 시험이 초록이면 다음이 <b>전부</b> 맞다는 뜻이다.
 * <ul>
 *   <li>엔티티와 DB 스키마가 어긋나지 않는다 — {@code ddl-auto: validate} 라 어긋나면 기동이 막힌다.
 *       그리고 그 스키마는 Flyway 가 <b>V1 부터</b> 깐 것이라 마이그레이션 자체가 같이 검증된다</li>
 *   <li>리포지토리의 파생 질의·수기 JPQL 이 부팅 때 해석된다(이름 오타는 여기서 죽는다)</li>
 *   <li>{@code @Qualifier("nightExecutor")} 같은 <b>빈 이름</b>이 실제로 맞는다</li>
 *   <li>이벤트 리스너가 빈으로 올라온다 — 시험 코드에 이름조차 없던 것들이다</li>
 *   <li>설정 스위치가 뜻대로 걸린다(개발 시계는 켜지고, 실제 생성은 꺼진다)</li>
 * </ul>
 *
 * <h3>★ "뜨기만 하면 된다" 로 두지 않는다</h3>
 * 빈이 올라온 것과 <b>쓰이는 그 이름</b>으로 올라온 것은 다른 말이다. 그래서 이름과 값을 함께 본다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 1 — 컨텍스트가 진짜로 뜬다")
class ContextLoadsIT extends ZzalItSupport {

    @Autowired PieceCompletedListener pieceCompletedListener;
    @Autowired PetHatchListener petHatchListener;
    @Autowired BakeTrigger bakeTrigger;
    @Autowired NightSweep nightSweep;
    @Autowired HatchService hatchService;
    @Autowired PipelineRegistry pipelineRegistry;
    @Autowired ImageClient imageClient;
    @Autowired TextClient textClient;
    @Autowired PostProcessor postProcessor;

    @Test
    @DisplayName("커밋 뒤에 도는 것들이 빈으로 올라와 있다")
    void listenersAreWired() {
        assertThat(pieceCompletedListener).isNotNull();
        assertThat(petHatchListener).isNotNull();
        assertThat(bakeTrigger).isNotNull();
    }

    @Test
    @DisplayName("실행기는 이름·크기까지 설계대로다 — 이름이 어긋나면 굽기가 아무 데도 안 간다")
    void executorsAreWiredByName() {
        ThreadPoolTaskExecutor hatch = context.getBean("hatchExecutor", ThreadPoolTaskExecutor.class);
        assertThat(hatch.getCorePoolSize()).as("동시에 구울 수 있는 부화 수").isEqualTo(3);
        assertThat(hatch.getThreadNamePrefix()).isEqualTo("hatch-");

        ThreadPoolTaskExecutor night = context.getBean("nightExecutor", ThreadPoolTaskExecutor.class);
        assertThat(night.getCorePoolSize()).as("밤 굽기는 부화와 자리를 안 나눈다").isEqualTo(2);
        assertThat(night.getThreadNamePrefix()).isEqualTo("night-");
    }

    @Test
    @DisplayName("@Async 가 실제로 켜져 있다 — 안 켜져 있으면 부화가 요청 스레드를 붙든다")
    void asyncIsEnabled() {
        assertThat(AopUtils.isAopProxy(hatchService))
                .as("HatchService.hatch 는 @Async(\"hatchExecutor\") 라 프록시여야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("시각 트리거는 하나뿐이고 23:00 KST 다")
    void theOnlyScheduledJobIsTheNightSweep() throws Exception {
        Method sweep = NightSweep.class.getMethod("sweep");
        Scheduled scheduled = sweep.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 0 23 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        assertThat(nightSweep).isNotNull();
    }

    @Test
    @DisplayName("★ 돈이 나가는 길이 열려 있지 않다")
    void generationIsFake() {
        assertThat(imageClient).isInstanceOf(FakeImageClient.class);
        assertThat(textClient).isInstanceOf(FakeTextClient.class);
        assertThat(postProcessor).isInstanceOf(FakePostProcessor.class);
    }

    @Test
    @DisplayName("부화는 v2 로 뜬다 — 프롬프트 4종이 다 있어야 v2 이고, 없으면 조용히 v1 로 내려간다")
    void hatchPipelineIsV2() {
        assertThat(pipelineRegistry.currentVersion(GenKind.HATCH)).isEqualTo("v2");
        assertThat(pipelineRegistry.steps(GenKind.HATCH, "v2")).hasSize(5);
    }

    @Test
    @DisplayName("개발 시계가 켜져 있다 — 시험이 시간을 밀 수 있어야 한다")
    void devClockIsAvailable() {
        assertThat(context.getBeanNamesForType(DevClockController.class))
                .as("app.zzal.dev-tools 가 false 면 이 컨트롤러는 빈으로 안 올라온다(주소 자체가 404)")
                .isNotEmpty();
    }

    @Test
    @DisplayName("스키마는 Flyway 가 V1 부터 전부 깔았다 — 마이그레이션도 매번 검증된다")
    void flywayAppliedEveryMigrationFromV1() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success = true and version is not null "
                        + "order by installed_rank", String.class);
        assertThat(versions).as("적용된 마이그레이션").contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }
}
