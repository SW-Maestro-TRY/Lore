package com.lore.zzal.pet;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 펫 시간대 — 화면이 보낸 {@code X-Zzal-Tz} 헤더를 읽고 검증한다.
 *
 * <h3>규칙 (2026-10-26 상훈 결정)</h3>
 * <ul>
 *   <li>펫마다 {@code zzal_pet.time_zone}(IANA 이름) 하나. NULL = 아직 관측 못 함 → 읽는 쪽은 전부 {@link ZzalRules#ZONE}(KST)</li>
 *   <li>펫 생성 때 헤더 값을 적는다. NULL 인 펫은 <b>헤더가 실린 첫 요청</b>에서 한 번 적는다. 적힌 값은 다시 안 바꾼다
 *       — 하루 중간에 시간대가 바뀌면 23시가 두 번 오거나 안 오고, 날짜로 찾는 기록(채팅 세션·밤 굽기·출석)이 어긋난다</li>
 *   <li>못 읽는 값(빈 값·오타·64자 초과)은 버린다. 그러면 NULL 로 남고 KST 로 동작한다</li>
 * </ul>
 * 부화 하루 상한·분석·운영 경보·S3 보관·관리자 화면은 일부러 KST 에 남긴다(이 클래스를 안 쓴다).
 */
public final class PetTimeZones {

    /** 화면이 {@code Intl.DateTimeFormat().resolvedOptions().timeZone} 을 실어 보내는 헤더. */
    public static final String HEADER = "X-Zzal-Tz";

    /** DB 칸 길이(varchar 64). IANA 이름 중 가장 긴 것도 30자 남짓이다. */
    public static final int MAX_LENGTH = 64;

    private PetTimeZones() {
    }

    /** 헤더 값을 검증한다. 못 읽으면 빈 값. 정상이면 {@link ZoneId#normalized()} 전의 IANA 이름을 그대로 쓴다. */
    public static Optional<ZoneId> parse(String raw) {
        if (raw == null) return Optional.empty();
        String v = raw.trim();
        if (v.isEmpty() || v.length() > MAX_LENGTH) return Optional.empty();
        try {
            return Optional.of(ZoneId.of(v));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /** 저장된 칸 값 → 규칙에 쓸 시간대. NULL·깨진 값은 KST. */
    public static ZoneId effective(String stored) {
        return parse(stored).orElse(ZzalRules.ZONE);
    }

    /**
     * 지금 처리 중인 HTTP 요청의 헤더. 요청 밖(배치·시험)이면 빈 값.
     *
     * ★ 컨트롤러마다 {@code @RequestHeader} 를 다는 대신 여기서 읽는다 — 펫을 만지는 모든 길이
     *   {@code PetService.touch} 하나를 지나므로, 그 한 곳에서 "NULL 이면 한 번 적기" 를 하면 빠지는 경로가 없다.
     */
    public static Optional<ZoneId> fromCurrentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servlet)) return Optional.empty();
        HttpServletRequest request = servlet.getRequest();
        return parse(request.getHeader(HEADER));
    }
}
