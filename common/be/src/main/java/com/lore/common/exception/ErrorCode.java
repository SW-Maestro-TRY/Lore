package com.lore.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 서비스 전체가 공유하는 에러 코드.
 *
 * 프론트는 사람이 읽는 문구(message)가 아니라 이 코드(name)로 분기한다.
 * 문구는 나중에 바뀌지만 코드는 안 바뀌기 때문이다.
 *
 * 도메인이 늘면 접두어로 구분한다. (COMMON_* / ZZAL_* / WEBTOON_* / TRAILER_*)
 */
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),

    // 인증·계정 (common)
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),
    REQUIRED_AGREEMENT_MISSING(HttpStatus.BAD_REQUEST, "필수 약관에 동의해야 가입할 수 있습니다"),
    // 아이디가 틀렸는지 비밀번호가 틀렸는지 알려주지 않는다 — 알려주면 어떤 이메일이
    // 가입돼 있는지 확인하는 수단이 된다.
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"),

    // 업로드 (common)
    INVALID_UPLOAD_KEY(HttpStatus.BAD_REQUEST, "올바르지 않은 이미지입니다"),
    UPLOAD_KEY_ALREADY_USED(HttpStatus.BAD_REQUEST, "이미 사용한 이미지입니다"),

    // 펫 (zzal)
    ZZAL_PET_NOT_FOUND(HttpStatus.NOT_FOUND, "펫을 찾을 수 없습니다"),
    // 남의 펫에 접근하면 403 이 아니라 404 를 준다 — 403 은 "그 번호의 펫이 존재한다" 는
    // 사실을 알려주는 셈이라, 번호를 훑어 남의 펫 수를 셀 수 있게 된다.
    ZZAL_PET_ALREADY_HATCHING(HttpStatus.CONFLICT, "아직 부화 중이에요"),
    ZZAL_PET_LIMIT_REACHED(HttpStatus.CONFLICT, "더 키울 수 있는 자리가 없어요"),

    // 돌보기 (zzal) — 왜 안 되는지를 사용자 말로 답한다. 화면은 이 문구를 그대로 띄워도 된다.
    ZZAL_PET_NOT_ALIVE(HttpStatus.CONFLICT, "아직 함께 지낼 수 없어요"),
    ZZAL_PET_SLEEPING(HttpStatus.CONFLICT, "자고 있어요"),
    ZZAL_PET_NOT_SLEEPING(HttpStatus.CONFLICT, "자고 있지 않아요"),
    ZZAL_CARE_NOT_NEEDED(HttpStatus.CONFLICT, "지금은 필요하지 않아요"),
    ZZAL_NO_FOOD(HttpStatus.CONFLICT, "밥이 다 떨어졌어요"),

    // 돌보기 v2 (zzal) — 플레이 정본 v1.2 이식(2026-09-05, #192). 계약은 zzal/docs/api-v2.md.
    // ★ 여기서 한 번에 다 적고 얼린다 — 백엔드·프론트·생성 세션이 동시에 도는 동안
    //   이 파일을 각자 건드리면 반드시 충돌한다.
    ZZAL_NOT_SLEEP_TIME(HttpStatus.CONFLICT, "아직 잘 시간이 아니에요"),
    ZZAL_NOT_WAKE_TIME(HttpStatus.CONFLICT, "아직 깰 시간이 아니에요"),
    ZZAL_SICK_REFUSES(HttpStatus.CONFLICT, "아파서 지금은 못 해요"),
    ZZAL_BATH_DONE_TODAY(HttpStatus.CONFLICT, "오늘은 이미 씻었어요"),
    ZZAL_CHAT_SLOT_CLOSED(HttpStatus.CONFLICT, "지금은 부르지 않았어요"),
    ZZAL_FEATURE_LOCKED(HttpStatus.CONFLICT, "아직 열리지 않았어요"),
    ZZAL_TRAVELING(HttpStatus.CONFLICT, "여행 중이에요"),
    ZZAL_NOT_TRAVELING(HttpStatus.CONFLICT, "여행 중이 아니에요"),
    ZZAL_MOTION_NOT_OPEN(HttpStatus.CONFLICT, "아직 배우지 않은 동작이에요"),
    ZZAL_REGEN_NOT_REQUESTED(HttpStatus.CONFLICT, "다시 굽기를 요청한 동작이 아니에요"),

    // ── v1 전용. 훈련·잠 길이표가 정본에서 사라져 쓸 곳이 없다. ──────────────
    // ★ 아직 지우지 않는 이유 — v1 컨트롤러·서비스가 살아 있는 동안은 컴파일이 깨진다.
    //   v1 경로를 걷어내는 PR(#192 PR-3)에서 아래 다섯 줄을 함께 지운다.
    //   프론트 common/fe/api/client.ts 의 코드 유니온도 그때 같이 갱신한다.
    @Deprecated ZZAL_PET_STILL_SLEEPING(HttpStatus.CONFLICT, "아직 자고 있어요"),
    @Deprecated ZZAL_TRAIN_IN_PROGRESS(HttpStatus.CONFLICT, "연습하고 있어요"),
    @Deprecated ZZAL_TRAIN_ENOUGH(HttpStatus.CONFLICT, "오늘 연습은 충분해요"),
    @Deprecated ZZAL_TRAIN_NOT_ENOUGH(HttpStatus.CONFLICT, "연습이 더 필요해요"),
    @Deprecated ZZAL_ALL_UNLOCKED(HttpStatus.CONFLICT, "이미 다 배웠어요"),

    // 놓아주기 (zzal) — 부화 중에는 보낼 수 없다. 알을 보내면 뒤에서 굽고 있는 생성이
    // 주인 없는 일이 되어, 돈은 나가는데 받을 펫이 없는 상태로 끝난다.
    ZZAL_PET_RELEASE_NOT_ALLOWED(HttpStatus.CONFLICT, "부화가 끝난 뒤에 보낼 수 있어요"),

    // ── 아래는 아직 안 만든 기능들이 쓸 코드다(2026-09-04 미리 확정) ──────────
    //
    // ★ 여섯 갈래를 동시에 만들 예정이라, 각자 여기에 코드를 추가하면 반드시 충돌한다.
    //   쓸 코드를 먼저 다 적어 두고 이 파일을 얼린다.

    // 후기 (zzal)
    ZZAL_FEEDBACK_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "이미 후기를 남겼어요"),

    // 미니게임 (zzal)
    ZZAL_GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "진행 중인 놀이가 없어요"),
    ZZAL_GAME_FINISHED(HttpStatus.CONFLICT, "이미 끝난 놀이예요"),
    ZZAL_GAME_DAILY_LIMIT(HttpStatus.CONFLICT, "오늘은 충분히 놀았어요"),

    // 관리자
    // ★ 404 가 아니라 403 을 준다 — 관리자 화면의 존재 자체는 비밀이 아니고,
    //   404 로 감추면 권한 설정을 빠뜨렸을 때 "주소가 틀렸나" 로 헤매게 된다.
    ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 볼 수 있어요");

    // 도메인별 코드는 각 담당자가 아래에 추가한다.
    // 예) ZZAL_PET_NOT_FOUND(HttpStatus.NOT_FOUND, "펫을 찾을 수 없습니다"),
    //     WEBTOON_NOT_FOUND(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다"),

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
