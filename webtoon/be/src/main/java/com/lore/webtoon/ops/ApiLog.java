package com.lore.webtoon.ops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 웹툰 API 요청 한 번, 또는 뒤에서 도는 작업 한 번(2026-11-03).
 *
 * <p>화면 행동({@code webtoon_event})은 「무엇을 눌렀나」, 비용({@code webtoon_usage})은 「모델 호출에 얼마」를
 * 남긴다. 그 사이 — 어떤 API 를 어떤 값으로 불렀고, 얼마나 걸렸고, 왜 실패했나 — 가 비어 있어서 채운다.
 * 표의 칸 뜻은 마이그레이션 {@code V20261103_2355__webtoon_api_log.sql} 머리에 있다.
 */
@Entity
@Table(name = "webtoon_api_log")
public class ApiLog {

    public static final String API = "api";
    public static final String TASK = "task";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    Instant at;

    @Column(nullable = false, length = 10)
    String kind;

    @Column(nullable = false, length = 16)
    String method;

    @Column(nullable = false, length = 300)
    String path;

    @Column(nullable = false, length = 160)
    String shape;

    @Column(length = 500)
    String query;

    @Column(nullable = false)
    int status;

    @Column(nullable = false)
    int ms;

    @Column(name = "user_id")
    Long userId;

    @Column(name = "browser_uid", length = 64)
    String browserUid;

    @Column(name = "request_body", columnDefinition = "text")
    String requestBody;

    @Column(name = "response_body", columnDefinition = "text")
    String responseBody;

    @Column(columnDefinition = "text")
    String error;

    @Column(length = 120)
    String ref;

    protected ApiLog() {
    }

    ApiLog(Instant at, String kind, String method, String path, String shape) {
        this.at = at;
        this.kind = kind;
        this.method = cut(method, 16);
        this.path = cut(path, 300);
        this.shape = cut(shape, 160);
    }

    /** 뒤에서 도는 작업 한 줄. {@code ref} 는 그 작업이 쓴 비용 기록 번호(webtoon_usage.run_id). */
    public static ApiLog task(String name, String target, Long userId, long ms, boolean ok, String error, String ref) {
        ApiLog row = new ApiLog(Instant.now(), TASK, name, target, name);
        row.status = ok ? 200 : 500;
        row.ms = (int) Math.min(Integer.MAX_VALUE, Math.max(0, ms));
        row.userId = userId;
        row.error = cut(error, 2000);
        row.ref = cut(ref, 120);
        return row;
    }

    public Long getId() {
        return id;
    }

    public String getShape() {
        return shape;
    }

    public int getStatus() {
        return status;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getError() {
        return error;
    }

    public String getRef() {
        return ref;
    }

    static String cut(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
