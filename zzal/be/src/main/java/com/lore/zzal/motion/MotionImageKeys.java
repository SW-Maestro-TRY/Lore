package com.lore.zzal.motion;

/**
 * 펫 그림의 S3 키를 <b>여기 한 곳에서만</b> 조립한다.
 *
 * <h3>★★ 왜 한 곳인가</h3>
 * 전에는 같은 규약을 {@code PetResponses}·{@code ShareService}·{@code PostProcessStep} 이
 * <b>각자 문자열로</b> 만들었다. 규약이 하나 바뀔 때 한 곳만 고쳐지면 빌드도 기동도 부화도 전부
 * 성공한 채 <b>화면에서만</b> 빈 그림이 뜬다. 실제로 심화 공유가 기본 그림 경로를 가리킨 적이 있다.
 * 조립을 한 자리로 모으면 규약이 갈릴 자리가 사라진다.
 *
 * <h3>★★ 판 번호가 주소에 들어간다 — 덮어쓰기를 없애기 위해서다</h3>
 * 판 번호가 없으면 다시 구운 그림이 <b>같은 주소</b>로 올라간다. 업로드는 성공하고 DB 도 맞는데
 * CDN 이 1년 캐시(정적 에셋과 같은 규칙)를 들고 있어 <b>옛 그림이 계속 나간다.</b>
 * 주소에 판이 들어가면 다시 구운 것은 언제나 새 주소라, 그 1년 캐시가 오히려 맞는 설정이 된다.
 *
 * <pre>
 *   기본  images/zzal/pets/{petId}/basic/{판}/{key}.webp      판 = 그 펫의 기본 그림을 구운 횟수
 *   앵커  images/zzal/pets/{petId}/basic/{판}/anchors.json    (그림과 같은 자리)
 *   심화  images/zzal/pets/{petId}/motions/{motionId}/{판}/motion.webp   판 = 그 동작의 attempts
 * </pre>
 *
 * <h3>★ 판이 없는 옛 펫 — 터지지 않고 옛 주소를 그대로 준다</h3>
 * {@code 판 <= 0} 이면 <b>판 칸을 아예 넣지 않는다.</b> 기존 zzal 데이터는 v4 배포와 함께 비울 예정이라
 * 소급은 필요 없지만, 값이 0 인 행(옛 행·아직 안 구운 행)이 섞였을 때 {@code .../basic/0/base.webp}
 * 같은 <b>있지도 않은 주소</b>를 내려보내면 화면이 빈 그림을 그린다. 옛 규약 주소를 주면 적어도
 * 옛 펫은 그대로 보이고, 새 펫은 첫 판이 1이라 이 갈래를 타지 않는다.
 *
 * <h3>★ 심화 판이 {@code regenRound} 가 아니라 {@code attempts} 인 이유</h3>
 * {@link ZzalMotion#queue(java.time.LocalDate)} 가 {@code regenRound} 를 <b>0 으로 되돌린다</b>
 * (그 값은 "이번 밤에 맥미니를 몇 번 썼나" 이지 평생 횟수가 아니다). 그걸 주소에 쓰면 다음 밤이
 * 같은 주소를 덮어써 판 번호를 넣은 뜻이 사라진다. {@code attempts} 는 되돌아가지 않는다.
 */
public final class MotionImageKeys {

    private static final String PET = "images/zzal/pets/%d";

    /**
     * 앵커({@code anchors.json})를 내는 부화 파이프라인 버전.
     *
     * ★ 한 곳에만 적는다 — 후처리가 "내야 한다" 고 보는 버전과 응답이 "있다" 고 말하는 버전이
     *   갈리면, 화면은 있지도 않은 주소를 받거나 있는 앵커를 못 받는다. 둘 다 오류가 안 난다.
     * ★ v1·v2 의 스크립트는 앵커를 아예 만들지 않는다.
     */
    public static final java.util.Set<String> ANCHOR_VERSIONS = java.util.Set.of("v4");

    private MotionImageKeys() {
    }

    /**
     * 그 펫에게 앵커 파일이 실제로 있나 — 버전이 내는 버전이고, 판이 한 번이라도 올라갔을 때.
     *
     * ★ 버전이 비어 있는 옛 기록도 그냥 "없다" 로 답한다. 여기서 터지면 펫 상세 전체가 500 이 된다.
     */
    public static boolean hasAnchors(String hatchPipelineVersion, int round) {
        return round > 0 && hatchPipelineVersion != null && ANCHOR_VERSIONS.contains(hatchPipelineVersion);
    }

    /** 기본 행동 그림이 쌓이는 자리. 후처리가 여기에 올린다. */
    public static String basicPrefix(long petId, int round) {
        String base = (PET + "/basic").formatted(petId);
        return round <= 0 ? base : base + "/" + round;
    }

    /** 기본 행동 한 칸. {@code key} 는 카탈로그 key(base·eat·…). */
    public static String basic(long petId, int round, String key) {
        return "%s/%s.webp".formatted(basicPrefix(petId, round), key);
    }

    /**
     * 앵커 파일 — 그림과 <b>같은 자리</b>에 둔다.
     *
     * ★ 같은 자리인 이유 — 앵커는 그 판의 그림을 설명하는 값이다. 다른 곳에 두면 판이 올라갈 때
     *   그림과 앵커가 서로 다른 판을 가리키게 되고, 그 어긋남은 화면에서만 드러난다.
     */
    public static String anchors(long petId, int round) {
        return basicPrefix(petId, round) + "/anchors.json";
    }

    /**
     * v1 부화의 8상태 파일. 그 버전은 {@code basic/} 규약 이전이라 펫 폴더 바로 아래다.
     *
     * ★ 판 번호가 없다 — v1 은 더 굽지 않는다(옛 펫을 설명만 한다).
     */
    public static String legacyState(long petId, String legacyFile) {
        return (PET + "/%s.webp").formatted(petId, legacyFile);
    }

    /** v1 부화 후처리의 출력 자리(펫 폴더 바로 아래). */
    public static String legacyStatePrefix(long petId) {
        return PET.formatted(petId);
    }

    /** 심화 행동(16프레임) 한 판이 쌓이는 자리. */
    public static String advancedPrefix(long petId, long motionId, int round) {
        String base = (PET + "/motions/%d").formatted(petId, motionId);
        return round <= 0 ? base : base + "/" + round;
    }

    /** 심화 행동 움짤. 파일 이름은 후처리 스크립트와의 약속이라 고정이다. */
    public static String advanced(long petId, long motionId, int round) {
        return advancedPrefix(petId, motionId, round) + "/motion.webp";
    }
}
