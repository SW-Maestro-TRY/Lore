package com.lore.webtoon;

/**
 * 웹툰 API 의 주소 앞자리 — <b>버전이 여기 한 곳에만 있다.</b>
 *
 * <h2>왜 도메인 뒤에 버전을 붙이나</h2>
 *
 * 이 저장소에는 두 가지가 이미 같이 있다:
 *
 * <pre>
 *   공통   /api/v1/auth · /api/v1/users · /api/v1/credits   버전이 /api 바로 뒤
 *   zzal   /api/zzal/v1/... · /api/zzal/v2/...              버전이 도메인 뒤
 * </pre>
 *
 * 웹툰은 <b>zzal 과 같은 쪽</b>을 따른다. 도메인마다 따로 올릴 수 있어야 하기
 * 때문이다 — zzal 은 이미 v2 인데 공통은 v1 이다. 만약 {@code /api/v1/webtoon}
 * 으로 두면 웹툰만 v2 로 올릴 때 {@code /api/v2/webtoon} 이 되어, 공통까지
 * v2 가 된 것처럼 읽힌다.
 *
 * <h2>바꿀 때</h2>
 *
 * 여기 한 줄만 고치면 컨트롤러 다섯이 같이 따라온다. 다만 <b>서버 밖</b>에도
 * 이 주소를 아는 곳이 있으니 같이 고쳐야 한다:
 *
 * <ul>
 *   <li>{@code common/be} 의 {@code WebSecurityConfig} — {@code /my/**} 만
 *       로그인을 요구하므로, 앞자리가 어긋나면 그 규칙이 안 걸려 조용히
 *       열린다(막히는 게 아니라 열리는 쪽이라 더 위험하다)</li>
 *   <li>{@code webtoon/fe} 의 {@code lib/nhApi.ts} · {@code lib/editorCore.ts}</li>
 *   <li>{@code haeun/landing} 의 {@code s3_upload.py} · {@code usage_report.py}
 *       · {@code spend_report.py} — 다 그린 뒤 서버에 되짚어 알리는 쪽</li>
 * </ul>
 */
public final class WebtoonApi {

    /** 지금 쓰는 판. */
    public static final String V1 = "/api/webtoon/v1";

    private WebtoonApi() {
    }
}
