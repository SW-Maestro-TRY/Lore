package com.lore.webtoon.work;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 예시 작품 관리자 API(#614). <b>관리자 계정({@code role = ADMIN})만</b> 쓴다.
 *
 * <h2>번들을 올리는 길이 둘이다</h2>
 *
 * <ul>
 *   <li>{@code POST /import} — 요청 본문으로 zip 을 바로 올린다. 로컬 · dev 와 작은 번들용이다.</li>
 *   <li>{@code POST /upload-url} → S3 로 직접 올림 → {@code POST /import-key} — <b>운영·staging 은 이쪽</b>이다.
 *       CloudFront 앞단 WAF({@code SizeRestrictions_BODY})가 큰 본문을 403 으로 막아서 몇 MB 짜리 번들은
 *       서버로 못 온다(게스트 사진 업로드가 같은 이유로 presign 을 쓴다, {@code JobController#photoPresign}).
 *       브라우저가 S3 로 직접 올리고 서버는 키만 받아 읽는다.</li>
 * </ul>
 *
 * 앱이 CSRF 를 꺼 둔 쿠키 인증이라(WebSecurityConfig) 이 주소들은 SameSite 쿠키와 아래 관리자 확인에 기댄다.
 * 누가 언제 올렸는지는 로그에 남긴다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/admin/examples")
public class ExampleAdminController {

    private static final Logger log = LoggerFactory.getLogger(ExampleAdminController.class);

    /** 요청 본문으로 받는 zip 의 상한. 번들 풀린 크기 상한({@link ExampleBundles})보다 작게 둔다. */
    static final long MAX_UPLOAD_BYTES = 80L * 1024 * 1024;

    private final Admins admins;
    private final ExampleAdmin admin;
    private final ExampleImporter importer;
    private final ExampleBundleExporter exporter;
    private final S3Service uploads;
    private final S3Storage storage;

    public ExampleAdminController(Admins admins, ExampleAdmin admin, ExampleImporter importer,
                                  ExampleBundleExporter exporter, S3Service uploads, S3Storage storage) {
        this.admins = admins;
        this.admin = admin;
        this.importer = importer;
        this.exporter = exporter;
        this.uploads = uploads;
        this.storage = storage;
    }

    /** 로그인했고 관리자인 사람의 번호. 아니면 던진다. */
    private Long requireAdmin() {
        Long me = CreditGate.currentUser();
        if (me == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다");
        }
        if (!admins.isAdmin(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관리자만 쓸 수 있습니다");
        }
        return me;
    }

    @Operation(summary = "예시 작품 목록", description = "순서대로. 비공개로 내려 둔 것도 보인다.")
    @GetMapping
    public Map<String, Object> list() {
        requireAdmin();
        return Map.of("examples", admin.list());
    }

    @Operation(summary = "번들 올리기(본문)", description = "요청 본문이 번들 zip 이다. dryRun=true 면 검사만 한다. 운영은 upload-url 길을 쓴다.")
    @PostMapping(value = "/import", consumes = {"application/zip", "application/octet-stream"})
    public ExampleImporter.Result importBody(HttpServletRequest request,
                                             @RequestParam(defaultValue = "false") boolean dryRun) throws IOException {
        Long me = requireAdmin();
        if (request.getContentLengthLong() > MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "번들이 너무 큽니다(" + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB까지). 큰 번들은 upload-url 로 올리세요.");
        }
        Path tmp = Files.createTempFile("example-bundle-", ".zip");
        try {
            copyBounded(request.getInputStream(), tmp, MAX_UPLOAD_BYTES);
            return run(me, tmp, dryRun, "body");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Operation(summary = "번들 올릴 주소 발급", description = "S3 로 직접 올릴 임시 주소를 받는다(10분). 올린 뒤 import-key 에 key 를 넘긴다.")
    @PostMapping("/upload-url")
    public S3Service.PresignedUpload uploadUrl() {
        Long me = requireAdmin();
        return uploads.createUploadUrl(me, "webtoon", "application/zip");
    }

    @Operation(summary = "번들 올리기(S3 키)", description = "upload-url 로 올린 zip 을 서버가 읽어 심는다. 심은 뒤 올린 파일은 지운다.")
    @PostMapping("/import-key")
    public ExampleImporter.Result importKey(@RequestBody KeyRequest body) throws IOException {
        Long me = requireAdmin();
        if (body == null || body.key() == null || body.key().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "key 가 없습니다");
        }
        // 발급받은 사람의 것이고 아직 안 쓴 키만 받는다 — 남의 키나 아무 키를 읽게 두지 않는다.
        uploads.consume(me, body.key(), Instant.now());
        Path tmp = Files.createTempFile("example-bundle-", ".zip");
        try {
            storage.download(body.key(), tmp);
            if (Files.size(tmp) > MAX_UPLOAD_BYTES) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "번들이 너무 큽니다");
            }
            return run(me, tmp, Boolean.TRUE.equals(body.dryRun()), "s3");
        } finally {
            Files.deleteIfExists(tmp);
            try {
                storage.delete(List.of(body.key()));        // 번들에는 사용자 입력이 들어 있다 — 남겨 두지 않는다
            } catch (RuntimeException e) {
                log.warn("올린 번들을 지우지 못했습니다 (key={})", body.key(), e);
            }
        }
    }

    @Operation(summary = "작품을 번들로 내보내기", description = "이 환경의 작품 하나를 zip 으로 받는다. 다른 환경에 올리면 예시가 된다.")
    @GetMapping("/{runId}/bundle")
    public ResponseEntity<byte[]> bundle(@PathVariable String runId) throws IOException {
        Long me = requireAdmin();
        ExampleBundle bundle = exporter.export(runId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExampleBundles.writeZip(bundle, out);
        log.info("예시 번들을 내보냈습니다 (admin={}, run={}, {}바이트)", me, runId, out.size());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"example-" + runId + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(out.toByteArray());
    }

    @Operation(summary = "예시 지정·해제·공개·순서", description = "보내지 않은 값은 그대로 둔다. 예시로 지정하면서 공개 여부를 안 정하면 공개로 둔다.")
    @PatchMapping("/{runId}")
    public ExampleAdmin.Row update(@PathVariable String runId, @RequestBody UpdateRequest body) {
        requireAdmin();
        return admin.update(runId, body.example(), body.visible(), body.order());
    }

    @Operation(summary = "예시 내리기", description = "예시를 해제하고 비공개로 돌린다. 작품을 지우지는 않는다.")
    @DeleteMapping("/{runId}")
    public ExampleAdmin.Row takeDown(@PathVariable String runId) {
        requireAdmin();
        return admin.takeDown(runId);
    }

    private ExampleImporter.Result run(Long admin, Path zip, boolean dryRun, String via) throws IOException {
        ExampleBundle bundle = ExampleBundles.fromZip(zip);
        ExampleImporter.Result result = importer.importBundle(bundle, dryRun);
        log.info("예시 번들을 올렸습니다 (admin={}, via={}, run={}, 상태={}, dryRun={})",
                admin, via, result.runId(), result.status(), dryRun);
        return result;
    }

    /** 상한을 넘으면 거기서 멈춘다 — Content-Length 를 속여도 실제로 읽은 바이트로 막는다. */
    private static void copyBounded(InputStream in, Path to, long limit) throws IOException {
        try (var out = Files.newOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > limit) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT, "번들이 너무 큽니다");
                }
                out.write(buf, 0, n);
            }
        }
    }

    /** {@code dryRun} 은 {@code Boolean} 이다 — 새 Jackson 은 기본값 없는 {@code boolean} 칸이 빠진 요청을 거절한다. */
    public record KeyRequest(String key, Boolean dryRun) {
    }

    /** {@code public} 는 자바 예약어라 {@code visible} 로 받는다. */
    public record UpdateRequest(Boolean example, @JsonProperty("public") Boolean visible, Integer order) {
    }
}
