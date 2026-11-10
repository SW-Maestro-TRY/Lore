package com.lore.common.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 페이앱 REST API 를 부르는 일만 한다. 규칙(언제 크레딧을 주나)은 {@link PaymentService} 가 안다.
 *
 * 문서: https://docs.payapp.kr (전체 원문 https://docs.payapp.kr/llms-full.txt)
 *
 * <h2>연동값 세 개</h2>
 *
 * 판매자 관리 사이트 → 설정 → 연동 정보에 있다. 셋 다 서버에만 둔다.
 * <ul>
 *   <li>{@code userid} — 판매자 아이디. 결제요청에 쓴다</li>
 *   <li>{@code linkkey} — 연동 KEY. 취소 API 에 쓰고, 통보 검증에도 쓴다</li>
 *   <li>{@code linkval} — 연동 VALUE. <b>통보 검증에만 쓴다.</b> 통보가 진짜 페이앱에서
 *       왔는지 가리는 비밀값이라 어디에도 보내지 않는다</li>
 * </ul>
 *
 * 비어 있으면 {@link #ready()} 가 false 이고, 그때 충전은 「준비 중」으로 막힌다.
 * <b>테스트용 별도 환경이 없다</b> — 키가 들어가면 실제 결제가 된다.
 */
@Component
public class PayAppClient {

    private static final Logger log = LoggerFactory.getLogger(PayAppClient.class);
    private static final String ENDPOINT = "https://api.payapp.kr/oapi/apiLoad.html";

    private final String userId;
    private final String linkKey;
    private final String linkVal;
    private final RestClient http;

    public PayAppClient(@Value("${lore.payapp.userid:}") String userId,
                        @Value("${lore.payapp.linkkey:}") String linkKey,
                        @Value("${lore.payapp.linkval:}") String linkVal) {
        this.userId = userId.trim();
        this.linkKey = linkKey.trim();
        this.linkVal = linkVal.trim();
        /* 문서 권장 30초. 기본은 무한히 기다림이라, 페이앱이 조용히 안 받는 날 요청
           스레드가 영영 붙잡힌다. */
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(5_000);
        timeouts.setReadTimeout(30_000);
        this.http = RestClient.builder().requestFactory(timeouts).build();
    }

    /** 연동값이 다 들어 있나. 아니면 결제창을 열 수 없다. */
    public boolean ready() {
        return !userId.isEmpty() && !linkKey.isEmpty() && !linkVal.isEmpty();
    }

    /**
     * 결제창을 만든다({@code cmd=payrequest}).
     *
     * 성공해도 <b>결제가 끝난 것이 아니다.</b> 결제창 주소를 받은 것뿐이고, 완료는
     * 통보(feedbackurl)로만 안다.
     */
    public PayRequestResult payRequest(PayRequest req) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("cmd", "payrequest");
        form.add("userid", userId);
        form.add("goodname", req.goodName());
        form.add("price", Integer.toString(req.price()));
        form.add("recvphone", req.phone());
        form.add("smsuse", "n");                 // 결제창은 화면이 연다. 문자로 또 보내지 않는다
        form.add("feedbackurl", req.feedbackUrl());
        form.add("returnurl", req.returnUrl());
        form.add("checkretry", "y");             // SUCCESS 를 못 받으면 페이앱이 다시 보낸다(최대 10번)
        form.add("var1", req.orderNo());
        if (req.buyerId() != null) {
            form.add("buyerid", req.buyerId());
        }
        Map<String, String> got = post(form);
        if (!"1".equals(got.get("state"))) {
            log.warn("페이앱 결제요청 실패 order={} code={} msg={}",
                    req.orderNo(), got.get("errorCode"), got.get("errorMessage"));
            return PayRequestResult.failed(got.getOrDefault("errorCode", ""),
                    got.getOrDefault("errorMessage", ""));
        }
        return PayRequestResult.ok(got.get("mul_no"), got.get("payurl"));
    }

    /**
     * 결제를 취소한다 — 전액 또는 일부({@code cmd=paycancel}). <b>정산 전</b> 결제만 된다.
     *
     * @param partWon 일부만 돌려줄 금액. null 이면 전액 취소
     */
    public CancelResult cancel(String mulNo, String memo, Integer partWon) {
        return cancelCall("paycancel", mulNo, memo, partWon);
    }

    /**
     * 정산이 끝난 결제의 환불을 요청한다({@code cmd=paycancelreq}). 성공해도 바로 취소된 것이
     * 아니라, 페이앱이 판매자 정산금에서 떼어 구매자에게 돌려주는 절차가 시작된 것이다.
     */
    public CancelResult cancelRequest(String mulNo, String memo, Integer partWon) {
        return cancelCall("paycancelreq", mulNo, memo, partWon);
    }

    private CancelResult cancelCall(String cmd, String mulNo, String memo, Integer partWon) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("cmd", cmd);
        form.add("userid", userId);
        form.add("linkkey", linkKey);
        form.add("mul_no", mulNo);
        form.add("cancelmemo", memo);
        if (partWon != null) {
            form.add("partcancel", "1");
            form.add("cancelprice", Integer.toString(partWon));
        } else {
            form.add("partcancel", "0");
        }
        Map<String, String> got = post(form);
        boolean ok = "1".equals(got.get("state"));
        if (!ok) {
            log.warn("페이앱 {} 실패 mul_no={} code={} msg={}", cmd, mulNo,
                    got.get("errorCode"), got.get("errorMessage"));
        }
        return new CancelResult(ok, got.getOrDefault("errorCode", ""), got.getOrDefault("errorMessage", ""));
    }

    /**
     * @param errorCode 80010 · 80020 이면 "취소할 수 있는 상태가 아님" — 보통 정산이 이미 끝난 것이다
     */
    public record CancelResult(boolean ok, String errorCode, String errorMessage) {
        /** 정산이 끝나서 바로 취소가 안 되는 경우인가. 그때는 환불 요청으로 다시 간다. */
        public boolean notCancellable() {
            return "80010".equals(errorCode) || "80020".equals(errorCode)
                    || "70050".equals(errorCode);
        }
    }

    /**
     * 통보가 진짜 페이앱에서 온 것인가 — userid · linkkey · linkval 셋이 다 맞아야 한다.
     *
     * 시간이 일정한 비교를 쓴다. 보통 비교는 앞에서부터 틀린 자리에서 멈춰서, 응답
     * 시간을 재면 한 글자씩 맞혀 갈 수 있다.
     */
    public boolean authentic(String userIdIn, String linkKeyIn, String linkValIn) {
        if (!ready()) {
            return false;
        }
        return same(userId, userIdIn) & same(linkKey, linkKeyIn) & same(linkVal, linkValIn);
    }

    private static boolean same(String want, String got) {
        if (got == null) {
            return false;
        }
        return MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8),
                got.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> post(MultiValueMap<String, String> form) {
        try {
            String body = http.post().uri(ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return parse(body);
        } catch (RestClientException e) {
            log.warn("페이앱 호출 실패: {}", e.toString());
            return Map.of("state", "0", "errorCode", "99999", "errorMessage", "연결 오류");
        }
    }

    /** 응답은 {@code state=1&errorMessage=&mul_no=…} 꼴이다. */
    static Map<String, String> parse(String body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        for (String pair : body.trim().split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    /**
     * @param phone   구매자 휴대폰 번호(숫자만). 페이앱 필수값이라 받지만 우리는 안 남긴다
     * @param buyerId 페이앱 판매자 화면에서 누가 샀는지 알아보려는 값. 계정 id
     */
    public record PayRequest(String orderNo, String goodName, int price, String phone,
                             String buyerId, String feedbackUrl, String returnUrl) {
    }

    public record PayRequestResult(boolean ok, String mulNo, String payUrl,
                                   String errorCode, String errorMessage) {
        static PayRequestResult ok(String mulNo, String payUrl) {
            return new PayRequestResult(true, mulNo, payUrl, null, null);
        }

        static PayRequestResult failed(String code, String message) {
            return new PayRequestResult(false, null, null, code, message);
        }
    }
}
