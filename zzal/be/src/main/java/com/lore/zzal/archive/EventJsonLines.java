package com.lore.zzal.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 보관 파일의 생김새 — 한 줄에 기록 하나(JSON Lines), 통째로 gzip.
 *
 * <h3>★ 왜 JSON Lines 인가</h3>
 * <ul>
 *   <li><b>줄 단위라 이어 붙일 수 있다.</b> 한 줄이 깨져도 그 줄만 버리고 나머지를 읽는다.
 *       하나의 큰 JSON 배열로 두면 파일 끝이 잘린 순간 <b>파일 전체를 못 읽는다</b></li>
 *   <li>Athena · DuckDB · pandas 가 그대로 읽는다. 옮겨 갈 계정에서 표를 만들 필요도 없다</li>
 *   <li>CSV 와 달리 칸이 늘어도 옛 파일이 안 깨진다 — 없는 키는 그냥 없다</li>
 * </ul>
 *
 * <h3>★ 왜 압축하나</h3>
 * 행동 기록은 같은 글자가 줄마다 반복된다({@code "device":"mobile"} · 경로 · 이벤트 이름).
 * 실측 계열의 데이터에서 gzip 은 보통 열 배 가까이 줄어든다. 보관은 <b>넣어 두고 거의 안 꺼내는</b>
 * 데이터라 용량이 곧 매달 나가는 돈이다. 쪼갤 수 없는 압축이지만, 한 파일이 수천 줄 규모라
 * 나눠 읽을 이유가 없다.
 *
 * <h3>★ {@code props} 는 문자열 그대로 둔다</h3>
 * 원본 칸이 JSON 문자열이라 풀어서 넣고 싶어지지만, 그러면 <b>줄마다 다른 모양</b>이 된다
 * ({@code stars} 가 어느 줄엔 숫자, 어느 줄엔 없음). Athena 는 그런 파일에서 칸의 타입을
 * 못 정한다. 문자열로 두면 읽는 쪽이 필요할 때 {@code json_parse} 한 번이면 된다.
 *
 * <h3>★ 시각은 ISO-8601 UTC 문자열</h3>
 * 밀리초 숫자로 두면 사람이 못 읽고, 지역 시각으로 두면 계정을 옮긴 뒤 기계의 시간대에 따라
 * 뜻이 달라진다. 칸을 가르는 날짜(KST)는 경로에 이미 있다.
 */
public final class EventJsonLines {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private EventJsonLines() {
    }

    /** 한 줄. 값이 없는 칸도 {@code null} 로 남긴다 — 줄마다 키가 같아야 읽는 쪽이 편하다. */
    public static String line(EventRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.id());
        out.put("name", row.name());
        out.put("anon_id", row.anonId());
        out.put("user_id", row.userId());
        out.put("props", row.props());
        out.put("path", row.path());
        out.put("referrer", row.referrer());
        out.put("source", row.source());
        out.put("device", row.device());
        out.put("variant", row.variant());
        out.put("occurred_at", iso(row.occurredAt()));
        out.put("received_at", iso(row.receivedAt()));
        try {
            return JSON.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 줄들을 gzip 으로 {@code to} 에 쓴다. 돌려주는 값은 쓰인 바이트 수.
     *
     * ★ 메모리에 통째로 만들지 않고 흘려 쓴다 — 한 번에 읽는 줄 수에 상한이 있어도
     *   그 줄들을 다시 한 덩어리 문자열로 만들면 상한을 둔 뜻이 없어진다.
     */
    public static long writeGzip(List<EventRow> rows, Path to) throws IOException {
        try (OutputStream raw = Files.newOutputStream(to);
             GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            for (EventRow row : rows) {
                gz.write(line(row).getBytes(StandardCharsets.UTF_8));
                gz.write('\n');
            }
        }
        return Files.size(to);
    }

    private static String iso(Instant at) {
        return at == null ? null : ISO.format(at);
    }
}
