package com.lore.webtoon.runs;

import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 편집실 스포이드가 색을 읽을 <b>밑그림 파일</b>을 이 서버를 거쳐 준다.
 *
 * 화면의 그림은 302 로 S3 · CloudFront 주소에서 온다. 비공개 작품은 잠깐 열리는 S3 주소라 다른
 * 출처인데, 다른 출처 그림은 브라우저가 픽셀을 읽지 못하게 막는다(캔버스가 더럽혀진다). 같은
 * 출처로 한 번 받아 두면 스포이드가 어느 작품에서나 된다. 굽기({@link BakeService})와 같은 그림
 * (가장 큰 폭)이라 고른 색이 구운 그림의 색과 같다.
 */
@Service
public class PagePixels {

    private static final Logger log = LoggerFactory.getLogger(PagePixels.class);

    private final PageStore pages;
    private final S3Storage storage;

    public PagePixels(PageStore pages, S3Storage storage) {
        this.pages = pages;
        this.storage = storage;
    }

    /** 이 장의 밑그림 파일 그대로. 없으면 {@code null}. */
    public byte[] raw(String runId, int no) {
        String key = pages.keysOf(runId).get(no);
        if (key == null) {
            return null;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("lore-pixels-", ".img");
            storage.download(key, tmp);
            return Files.readAllBytes(tmp);
        } catch (IOException | RuntimeException e) {
            log.warn("스포이드용 그림을 못 가져왔습니다 (run={}, 장={})", runId, no, e);
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 임시 파일 — 운영체제가 치운다
                }
            }
        }
    }

    /** 파일 머리로 종류를 본다. PNG 가 아니면 JPEG 로 둔다(올리는 쪽이 이 둘만 만든다). */
    static String typeOf(byte[] bytes) {
        boolean png = bytes.length > 4 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        return png ? "image/png" : "image/jpeg";
    }
}
