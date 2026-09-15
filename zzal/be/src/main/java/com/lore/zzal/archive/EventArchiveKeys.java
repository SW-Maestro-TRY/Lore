package com.lore.zzal.archive;

import java.time.LocalDate;

/**
 * 보관 파일이 S3 의 <b>어디에</b> 놓이는가.
 *
 * <h3>★★ {@code images/} 아래에는 절대 놓지 않는다</h3>
 * 그림 버킷의 {@code images/*} 는 CloudFront 가 <b>공개로</b> 내보내는 자리다
 * ({@code S3Service.KEY_PREFIX} 주석 — 그 접두사는 장식이 아니라 CDN 과 맺은 계약이다).
 * 행동 기록에는 익명 번호와 로그인 번호가 줄마다 들어간다. 그 두 가지가 같은 자리에 놓이면
 * 주소를 아는 사람은 누구나 받아 갈 수 있다. 그래서 뿌리 이름을 <b>여기서</b> 검사한다 —
 * 버킷을 잘못 적는 사고와 접두사를 잘못 적는 사고는 다른 사고라 각각 막아야 한다.
 *
 * <h3>★ 왜 한 뿌리 아래 날짜로 가르나</h3>
 * <pre>
 * {뿌리}/zzal/events/dt=2026-09-14/part-000000000001-000000005000.jsonl.gz
 * </pre>
 * <ol>
 *   <li><b>한 뿌리</b> — 계정이 통째로 사라지는 날(11/27) {@code aws s3 sync s3://옛/{뿌리}/ s3://새/{뿌리}/}
 *       한 줄로 옮긴다. 뿌리가 여럿이면 옮길 목록을 사람이 기억해야 하고, 기억은 반드시 하나를 빠뜨린다</li>
 *   <li><b>{@code zzal/events}</b> — 도메인과 종류. 나중에 webtoon 이나 다른 기록이 붙어도
 *       옆자리에 들어가고 서로의 경로를 침범하지 않는다</li>
 *   <li><b>{@code dt=YYYY-MM-DD}</b> — Hive 식 칸막이다. Athena·DuckDB·Glue 가 이 생김새를
 *       그대로 칸(partition)으로 읽는다. 날짜로 자르면 "9월 한 달" 을 훑을 때 그 폴더만 읽는다</li>
 *   <li><b>{@code part-{처음}-{끝}}</b> — 담은 {@code zzal_event.id} 구간. 이름이 구간에서
 *       <b>결정적으로</b> 나오므로, 올리다 죽어서 같은 구간을 다시 올려도 <b>같은 파일을 덮어쓴다</b>.
 *       일련번호(part-0001)로 두면 같은 구간이 다른 이름으로 두 번 쌓인다</li>
 * </ol>
 * 12자리로 채우는 것은 이름순 정렬이 곧 시간순이 되게 하려는 것이다.
 */
public final class EventArchiveKeys {

    /** 공개로 나가는 자리. 뿌리가 이것이면 보관을 시작하지 않는다. */
    public static final String PUBLIC_PREFIX = "images";

    private EventArchiveKeys() {
    }

    /**
     * 뿌리 이름이 쓸 수 있는 것인지. 쓸 수 있으면 {@code null}, 아니면 <b>사람이 읽을 이유</b>.
     *
     * ★ 참/거짓이 아니라 이유를 돌려준다 — 설정이 원인일 때는 설정 이름과 이유를 그대로 말해야
     *   어디를 고칠지 알 수 있다(2026-08-25 사고의 교훈).
     */
    public static String prefixProblem(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "app.zzal.archive.prefix (ZZAL_ARCHIVE_PREFIX) 가 비어 있습니다";
        }
        String p = prefix.trim();
        if (p.startsWith("/") || p.endsWith("/")) {
            return "app.zzal.archive.prefix 는 앞뒤에 / 를 붙이지 않습니다: " + prefix;
        }
        if (p.equals(PUBLIC_PREFIX) || p.startsWith(PUBLIC_PREFIX + "/")) {
            return "app.zzal.archive.prefix 가 공개로 나가는 자리(" + PUBLIC_PREFIX
                    + "/)입니다 — 행동 기록에는 익명 번호·로그인 번호가 들어갑니다: " + prefix;
        }
        return null;
    }

    /** {@code {뿌리}/zzal/events/dt=YYYY-MM-DD/part-{처음12}-{끝12}.jsonl.gz} */
    public static String partKey(String prefix, LocalDate dt, long fromEventId, long toEventId) {
        String problem = prefixProblem(prefix);
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        return "%s/zzal/events/dt=%s/part-%012d-%012d.jsonl.gz"
                .formatted(prefix.trim(), dt, fromEventId, toEventId);
    }
}
