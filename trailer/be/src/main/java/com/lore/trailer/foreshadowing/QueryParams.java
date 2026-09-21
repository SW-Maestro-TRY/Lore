package com.lore.trailer.foreshadowing;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;

import java.util.OptionalInt;

/**
 * 쿼리 인자 셋(회차 · 쪽 · 크기)을 글자에서 숫자로 읽는다.
 *
 * <h3>★ 왜 {@code @RequestParam int} 로 받지 않나</h3>
 * 그러면 {@code ?chapter=abc} 가 <b>500</b> 이 된다 — 변환 실패 예외를 400 으로 바꾸는 갈래가
 * {@code GlobalExceptionHandler} 에 없다. 보낸 쪽이 잘못한 것인데 서버가 터진 것처럼 보인다.
 * 그래서 글자로 받아 여기서 읽고, 틀리면 400 을 던진다(decisions.md 2-17).
 *
 * <h3>회차만 코드가 따로다</h3>
 * 회차가 틀리면 {@code TRAILER_INVALID_CHAPTER}, 쪽·크기는 공통 {@code INVALID_INPUT} 이다. 화면은
 * 회차 오류를 보면 저장해 둔 "판정 기준" 회차를 되돌려야 해서 따로 가려야 한다.
 * 회차가 가장 뒤 회차보다 큰지는 표를 봐야 알아서 서비스가 본다.
 */
final class QueryParams {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 100;

    private QueryParams() {
    }

    /** 독자가 읽은 회차 N. 필수. 없거나 숫자가 아니거나 1보다 작으면 400 {@code TRAILER_INVALID_CHAPTER}. */
    static int chapter(String raw) {
        OptionalInt parsed = parse(raw);
        if (parsed.isEmpty() || parsed.getAsInt() < 1) {
            throw new BusinessException(ErrorCode.TRAILER_INVALID_CHAPTER, "회차는 1 이상의 숫자여야 합니다");
        }
        return parsed.getAsInt();
    }

    /** 쪽 번호. 0부터. 없으면 0. */
    static int page(String raw) {
        if (isBlank(raw)) {
            return 0;
        }
        OptionalInt parsed = parse(raw);
        if (parsed.isEmpty() || parsed.getAsInt() < 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page 는 0 이상의 숫자여야 합니다");
        }
        return parsed.getAsInt();
    }

    /** 한 쪽의 크기. 없으면 50, 최대 100. */
    static int size(String raw) {
        if (isBlank(raw)) {
            return DEFAULT_SIZE;
        }
        OptionalInt parsed = parse(raw);
        if (parsed.isEmpty() || parsed.getAsInt() < 1 || parsed.getAsInt() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "size 는 1부터 %d까지입니다".formatted(MAX_SIZE));
        }
        return parsed.getAsInt();
    }

    private static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    /** 앞뒤 빈칸을 뗀 뒤 정수로. 숫자가 아니거나 int 를 넘으면 비어 있다. */
    private static OptionalInt parse(String raw) {
        if (isBlank(raw)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(raw.strip()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
