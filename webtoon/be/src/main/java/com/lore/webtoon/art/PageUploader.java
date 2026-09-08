package com.lore.webtoon.art;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.work.WorkLedger;
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
 */
@Service
public class PageUploader {

    private static final Logger log = LoggerFactory.getLogger(PageUploader.class);

    /** 키 폴더. common 이 아는 이름이어야 한다({@code S3Service.ALLOWED_DOMAINS}). */
    private static final String DOMAIN = "webtoon";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final S3Storage storage;
    private final PageStore pages;
    private final WorkLedger ledger;
    private final String bucket;

    public PageUploader(S3Storage storage, PageStore pages, WorkLedger ledger,
                        @Value("${app.s3.content-bucket:}") String bucket) {
        this.storage = storage;
        this.pages = pages;
        this.ledger = ledger;
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
        /* **공개 여부를 보고 적는다.**
         *
         * 올릴 때는 늘 열리는 자리에 올린다 — 올리는 쪽은 이 작품이 공개인지
         * 모르고, 알 필요도 없다. 비공개면 적으면서 CloudFront 가 안 내주는
         * 자리로 옮긴다.
         *
         * 이 한 줄을 빠뜨리면 <b>비공개 작품의 그림이 주소만 알면 누구나
         * 보이는 자리에 남는다.</b> 목록에서 가려질 뿐 파일은 열려 있다. */
        int fresh = pages.record(runId, done, ledger.isPublic(runId));
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
