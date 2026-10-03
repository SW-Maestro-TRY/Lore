package com.lore.webtoon.work;

import java.util.List;
import java.util.Map;

/**
 * 예시 작품 하나를 환경 밖으로 옮기는 <b>번들</b>(#614).
 *
 * <h2>왜 S3 키가 아니라 내용을 담나</h2>
 *
 * 환경마다 S3 가 다르다(로컬 {@code ~/lore-minio} · dev 박스의 MinIO · staging · 운영 각자의 버킷). 작품은 DB
 * 행과 S3 키(uuid)라서 키를 그대로 옮기면 다른 환경에서는 가리키는 그림이 없다. 번들은 그림 <b>내용</b>을
 * 들고 가고, 받는 쪽이 자기 창고에 새 키로 올린다({@link ExampleImporter}).
 *
 * <h2>모양</h2>
 *
 * 저장소의 예시 폴더({@code webtoon/ai/assets/examples/<run_id>/})와 같다. zip 으로 묶을 때는 그림을
 * {@code pages/} 아래에 두고 설명 파일 이름을 {@code manifest.json} 으로 쓴다(옛 이름 {@code meta.json}
 * 도 읽는다).
 *
 * <pre>
 *   manifest.json          제목 · 장르 · 캐릭터 · 그림체 · 줄거리 · 입력값 · 장면 설명
 *   pages/p01-w320.jpg     쪽 그림, 폭 두 개(목록 카드가 320, 본문이 1080)
 *   pages/p01-w1080.jpg
 *   run/…                  (선택) 하네스 작품 폴더 — 이야기 후보 · 시트 · 장면 · 검수
 * </pre>
 *
 * 원본 사진은 담지 않는다.
 */
public record ExampleBundle(Manifest manifest, List<Page> pages, Map<String, byte[]> runFiles) {

    /**
     * @param runId    작품 번호. 받는 쪽에서도 같은 번호로 심는다
     * @param captions 2쪽부터 차례로 붙는 장면 설명(1쪽은 표지)
     * @param input    사용자가 실제로 넣은 값(작업의 {@code input_json}). 없으면 이름만 적는다
     */
    public record Manifest(String runId, String title, String genre, String character, String style,
                           String logline, List<String> captions, Map<String, Object> input) {
    }

    /** 쪽 그림 한 장의 한 폭. */
    public record Page(int pageNo, int width, byte[] bytes) {
    }
}
