package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.UploadTicket;
import com.lore.common.s3.UploadTicketRepository;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 통합 시험이 공통으로 쓰는 것 — 뒷정리 · 로그인 · 재료 만들기 · 기다리기 · 개발 시계.
 *
 * <h3>★★ 뒷정리는 롤백이 아니라 비우기다</h3>
 * 시험을 {@code @Transactional} 로 감싸면 커밋이 없어 {@code AFTER_COMMIT} 리스너가 영영 안 돈다 —
 * 이 하네스가 보려던 자리가 통째로 사라진다. 그래서 <b>진짜로 커밋</b>하고, 시험이 끝나면
 * {@code public} 스키마의 표를 전부 {@code TRUNCATE … RESTART IDENTITY CASCADE} 한다.
 *
 * ★ 표 이름을 손으로 적지 않고 DB 에 묻는다. 적어 두면 표가 하나 늘 때 <b>조용히</b> 남고,
 *   남은 줄은 다음 시험을 엉뚱하게 통과시키거나 엉뚱하게 실패시킨다.
 *
 * <h3>★★ 비울 DB 를 이름으로 확인한다</h3>
 * 실수로 개발 DB({@code lore})를 가리킨 채 돌면 <b>되돌릴 수 없다.</b> 그래서 데이터베이스 이름이
 * {@code _test} 로 끝나지 않으면 컨텍스트를 만들기도 전에 멈춘다.
 */
public abstract class ZzalItSupport {

    /** 한 시험 안에서 겹치지 않는 메일 주소·키를 만들기 위한 번호. */
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired protected MockMvc mockMvc;
    /** 요청 본문을 만들 때만 쓴다 — 응답은 {@link JsonNode} 로 읽는다. */
    protected final ObjectMapper json = new ObjectMapper();
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ApplicationContext context;
    @Autowired protected TransactionTemplate transactions;

    @Autowired protected UserRepository userRepository;
    @Autowired protected UploadTicketRepository ticketRepository;
    @Autowired protected ZzalPetRepository petRepository;
    @Autowired protected PetService petService;
    @Autowired protected HatchService hatchService;
    @Autowired protected MotionSeeder motionSeeder;

    // ── 안전장치 ──────────────────────────────────────────────────────────

    /**
     * ★ 컨텍스트를 만들기 <b>전에</b> 돈다(JUnit 은 {@code @BeforeAll} 을 시험 인스턴스보다 먼저 부른다).
     *   그래서 잘못된 DB 에 붙어 보지도 못하고 멈춘다.
     */
    @BeforeAll
    static void refuseAnythingButATestDatabase() {
        String url = System.getenv("LORE_TEST_DB_URL");
        if (url == null || url.isBlank()) {
            return;     // 조건 애노테이션이 이미 건너뛰게 했다
        }
        String database = url.replaceAll("[?].*$", "").replaceAll("^.*/", "");
        assertThat(database)
                .as("통합 시험은 시험 전용 DB 에서만 돕니다 — 표를 비우기 때문입니다. "
                        + "LORE_TEST_DB_URL 의 데이터베이스 이름이 _test 로 끝나야 합니다(지금: %s)", database)
                .endsWith("_test");
    }

    @BeforeEach
    void assertPointedAtTestDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database).as("표를 비울 DB").endsWith("_test");
    }

    // ── 뒷정리 ────────────────────────────────────────────────────────────

    /**
     * 굽는 스레드가 아직 돌고 있는데 표를 비우면, 그 스레드가 방금 비운 자리에 줄을 다시 쓴다 —
     * 다음 시험이 남의 줄을 보고 엉뚱하게 판정한다. 먼저 조용해지기를 기다린다.
     */
    @AfterEach
    void emptyEveryTable() {
        awaitExecutorsIdle();
        List<String> tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history'",
                String.class);
        if (tables.isEmpty()) {
            return;
        }
        String list = String.join(", ", tables.stream().map(t -> "\"" + t + "\"").toList());
        jdbc.execute("TRUNCATE TABLE " + list + " RESTART IDENTITY CASCADE");
    }

    /** 부화·밤 굽기 실행기가 빌 때까지. 남아 있어도 시험을 깨뜨리지는 않는다(뒷정리일 뿐이다). */
    private void awaitExecutorsIdle() {
        for (String name : List.of("hatchExecutor", "nightExecutor")) {
            ThreadPoolTaskExecutor executor = context.getBean(name, ThreadPoolTaskExecutor.class);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline
                    && (executor.getActiveCount() > 0
                    || !executor.getThreadPoolExecutor().getQueue().isEmpty())) {
                sleep(20);
            }
        }
    }

    // ── 기다리기 ──────────────────────────────────────────────────────────

    /**
     * 조건이 참이 될 때까지 최대 {@code timeout}. 안 오면 <b>시험이 빨개진다</b>.
     *
     * ★ 실행기를 동기로 바꾸지 않는 대신 여기서 받는다 — 빈 이름·실행기 배선을 시험 안에 남기기 위해서다.
     *   "우연히 통과" 는 생기지 않는다: 조건이 끝내 안 오면 여기서 실패한다.
     */
    protected void await(String what, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError("%s — %d초를 기다렸지만 오지 않았습니다".formatted(what, timeout.toSeconds()));
    }

    protected void await(String what, BooleanSupplier condition) {
        await(what, Duration.ofSeconds(30), condition);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ── 로그인 ────────────────────────────────────────────────────────────

    /** 이 사람으로 부른다. JWT 는 만들지 않는다 — 컨트롤러가 보는 것은 {@code principal} 의 사용자 번호뿐이다. */
    protected RequestPostProcessor asUser(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    protected JsonNode getJson(Long userId, String path) throws Exception {
        return body(mockMvc.perform(get(path).with(asUser(userId))).andReturn());
    }

    protected MvcResult postAs(Long userId, String path, Object requestBody) throws Exception {
        var request = post(path).with(asUser(userId)).contentType(MediaType.APPLICATION_JSON);
        if (requestBody != null) {
            request = request.content(json.writeValueAsString(requestBody));
        }
        return mockMvc.perform(request).andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 실패 응답의 {@code error.code}. 없으면 null. */
    protected String errorCode(MvcResult result) throws Exception {
        JsonNode error = body(result).path("error");
        return error.isMissingNode() || error.isNull() ? null : error.path("code").asText(null);
    }

    // ── 개발 시계 (운영에 있는 그 장치를 그대로 쓴다) ──────────────────────

    /** 이 펫의 시계를 {@code by} 만큼 앞으로 민다 — {@code POST /api/zzal/v1/dev/pets/{id}/advance-clock}. */
    protected JsonNode advanceClock(Long userId, Long petId, Duration by) throws Exception {
        MvcResult result = postAs(userId, "/api/zzal/v1/dev/pets/%d/advance-clock".formatted(petId),
                java.util.Map.of("seconds", by.toSeconds()));
        assertThat(result.getResponse().getStatus())
                .as("개발 시계가 시험에서 안 돕니다(app.zzal.dev-tools). 응답=%s",
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        return body(result);
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    protected Long newUserId() {
        return userRepository.save(User.signUp("zzal-it-%d@example.invalid".formatted(SEQ.incrementAndGet())))
                .getId();
    }

    /**
     * 그림 한 장을 올린 것으로 친다 — presign 발급 기록만 만든다.
     * ({@code PetService.draft} 가 {@link S3Service#consume} 으로 이 기록을 반드시 확인한다.)
     */
    protected String newUploadedImageKey(Long userId) {
        String key = "images/zzal/%s".formatted(UUID.randomUUID());
        ticketRepository.save(UploadTicket.issue(userId, key, "zzal", "image/png", Instant.now()));
        return key;
    }

    /**
     * 부화를 다 끝낸 <b>3층 직전</b>의 펫 — 2층 8종이 전부 열려 있다.
     *
     * <h3>★ 어디까지가 지름길인가</h3>
     * 2층 8종의 조건은 채팅 12회·목욕 3회·잠 3회·게임 3회·무결점 3일이라 실제로 채우려면 며칠치
     * 호출이 필요하다. 그 며칠은 이 시험이 보려는 것이 아니므로 <b>누적 카운터만</b> 직접 앉힌다.
     * 조각이 열리는 판정({@code PetService.openPieces} → {@code UnlockRules} → {@code enablePieces})은
     * 지름길 없이 진짜로 돈다 — 다음 조회 한 번이 그것을 밟는다.
     *
     * ★ {@code layerTwoDoneAt} 만 한 발 앞으로 당긴다. 정본은 "다 열린 뒤 <b>처음 맞는 기상</b>" 이라
     *   당기지 않으면 하룻밤을 실제로 재워야 하고, 그동안 게이지가 흘러 돌보기가 거절될 수 있다 —
     *   그러면 시험이 보려는 것(조각 → 굽기)이 아니라 그날의 운에 걸린다.
     */
    protected ZzalPet aliveLayerTwoPet(Long userId) {
        Instant now = Instant.now();
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), now));
            created.character("여울", null, null, null, now);
            created.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", now);
            created.skipTutorial(now);
            ReflectionTestUtils.setField(created, "chatAnswers", 12);
            ReflectionTestUtils.setField(created, "sleepWakeCount", 3);
            ReflectionTestUtils.setField(created, "bathCount", 3);
            ReflectionTestUtils.setField(created, "gameStarts", 3);
            ReflectionTestUtils.setField(created, "zeroMissDays", 3);
            ReflectionTestUtils.setField(created, "layerTwoDoneAt", now.minusSeconds(60));
            return created;
        });
        motionSeeder.seed(pet.getId(), now);
        return pet;
    }
}
