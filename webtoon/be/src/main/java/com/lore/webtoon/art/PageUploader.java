package com.lore.webtoon.art;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 다 그린 그림을 S3 로 올리고 그 자리를 적는다.
 *
 * <h2>왜 자바가 올리나</h2>
 *
 * 전에는 파이썬이 다 했다 — 폭을 줄이고, boto3 로 올리고, 올린 주소를 이
 * 서버에 <b>HTTP 로 도로 알려</b> 줬다. 그래서 두 가지가 어긋났다.
 *
 * <b>하나.</b> 버킷 이름 · 키 규칙({@code images/<도메인>/<uuid>}) · 캐시
 * 헤더를 자바(common 의 {@link S3Service} · {@link S3Storage})와 파이썬이
 * 각자 한 벌씩 적어 두고 있었다. 두 벌은 반드시 어긋나고, 어긋나면 <b>올라
 * 가긴 하는데 읽을 때 403 이 난다</b> — 그 사고가 두 파일 주석에 똑같이
 * 적혀 있다. 이제 규칙을 아는 곳은 {@link S3Service#newKey} 하나다.
 *
 * <b>둘.</b> 올리는 것과 적는 것이 다른 일이었다. 알리는 쪽만 조용히 실패
 * 하면(내부 토큰이 없으면 그랬다 — 실제로 겪었다) 그림은 S3 에 있는데 DB 는
 * 비고, 화면에는 "올렸습니다" 가 찍힌다. 이제 올린 그 자리에서 바로 적으므로
 * 둘이 갈릴 수가 없다. 알림용 내부 주소도, 그 토큰도 필요 없다.
 *
 * <h2>줄이는 일은 그대로 파이썬이 한다</h2>
 *
 * 원본을 폭마다 줄이는 코드는 이미 파이썬에 있고 잘 돈다. 자바로 옮길 이유가
 * 없다 — 옮기면 그거야말로 두 벌이 된다. 파이썬은 만들어서 디스크에 놓고
 * <b>경로만</b> 알려 주고({@code s3_upload.py --prepare}), 여기서 그 파일을
 * 읽어 올린다. 둘은 같은 기계에 있으므로(이 서버가 그 스크립트를 프로세스로
 * 띄운다) 파일이 네트워크를 타지 않는다.
 */
@Service
public class PageUploader {

    private static final Logger log = LoggerFactory.getLogger(PageUploader.class);

    /** 키 폴더. common 이 아는 이름이어야 한다({@code S3Service.ALLOWED_DOMAINS}). */
    private static final String DOMAIN = "webtoon";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final S3Storage storage;
    private final PageStore pages;
    private final String bucket;

    public PageUploader(S3Storage storage, PageStore pages,
                        @Value("${app.s3.content-bucket:}") String bucket) {
        this.storage = storage;
        this.pages = pages;
        this.bucket = bucket == null ? "" : bucket.trim();
    }

    /** 버킷을 안 정해 뒀으면 안 올린다 — 로컬에서는 하네스 디스크로 충분하다. */
    public boolean ready() {
        return !bucket.isEmpty();
    }

    /**
     * {@code --prepare} 가 낸 JSON 을 받아 그 파일들을 올리고 적는다.
     *
     * @param prepared {@code {"<run_id>": [{page_no, width, path, bytes}, ...]}}
     * @return 새로 적은 줄 수
     */
    public int uploadPrepared(String runId, String prepared, Consumer<String> onLine) {
        List<PageStore.Upload> done = new ArrayList<>();
        JsonNode files;
        try {
            JsonNode root = JSON.readTree(prepared);
            files = root.path(runId);
            if (!files.isArray()) {
                files = root.elements().hasNext() ? root.elements().next() : null;
            }
        } catch (Exception bad) {                   // noqa: 만들기를 실패시키지 않는다
            log.error("올릴 목록을 못 읽었습니다 (run={})", runId, bad);
            return 0;
        }
        if (files == null || !files.isArray()) {
            log.warn("올릴 것이 없습니다 (run={})", runId);
            return 0;
        }

        for (Iterator<JsonNode> it = files.elements(); it.hasNext(); ) {
            JsonNode one = it.next();
            Path from = Path.of(one.path("path").asText(""));
            if (!Files.isRegularFile(from)) {
                log.warn("그릴 것이 그 자리에 없습니다 ({})", from);
                continue;
            }
            /* **한 장이 실패해도 나머지는 올린다.** 여기까지 왔으면 이미 돈을
               치른 작품이다 — 한 장 때문에 통째로 버리면 그 돈이 사라진다.
               못 올린 장은 다음에 다시 올릴 때 같이 올라간다. */
            try {
                String key = S3Service.newKey(DOMAIN);
                storage.upload(key, from, contentTypeOf(from));
                done.add(new PageStore.Upload(one.path("page_no").asInt(),
                                              one.path("width").asInt(),
                                              key, one.path("bytes").asLong()));
            } catch (RuntimeException fail) {
                log.error("그림 한 장을 못 올렸습니다 ({})", from, fail);
            }
        }

        if (done.isEmpty()) {
            return 0;
        }
        int fresh = pages.record(runId, done);
        if (onLine != null) {
            onLine.accept("[S3] 그림 " + done.size() + "개를 올리고 적었습니다");
        }
        return fresh;
    }

    /** 확장자로 본다. 여기 오는 것은 우리가 방금 만든 파일이라 이걸로 충분하다. */
    private static String contentTypeOf(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }
}
