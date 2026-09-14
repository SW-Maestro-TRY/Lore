package com.lore.zzal.guard;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 이 요청이 <b>어느 집에서</b> 왔나 — {@code X-Forwarded-For} 에서만 얻는다.
 *
 * <h3>★★ {@code request.getRemoteAddr()} 는 쓸모가 없다</h3>
 * 운영은 CloudFront → ALB → 우리 서버다. 서버가 보는 상대편은 <b>ALB</b> 라, 원격 주소를 세면
 * 세상 모든 사람이 한 칸에 들어간다. 예외도 로그도 없이 "모두가 같은 IP" 가 되어, IP 상한은
 * 켜져 있는데 아무도 못 지나가거나(전원 차단) 아무나 지나간다(사실상 꺼짐).
 *
 * <h3>★★ 맨 앞을 그대로 믿지 않는다</h3>
 * {@code X-Forwarded-For} 의 <b>맨 앞은 브라우저가 적어 보낸 값</b>이다. CloudFront 는 그 뒤에
 * 진짜 접속자를 덧붙이고, ALB 는 다시 그 뒤에 CloudFront 를 덧붙인다. 그래서 줄은 이렇게 된다.
 * <pre>
 *   X-Forwarded-For: (사용자가 적어 보낸 아무 값), (진짜 접속자), (CloudFront)
 *                     ↑ 믿으면 안 됨          ↑ 우리가 원하는 것   ↑ 우리 쪽 설비
 * </pre>
 * 맨 앞을 쓰면 <b>헤더 한 줄로 상한을 통째로 우회</b>할 수 있다. 그래서 <b>뒤에서부터</b> 센다 —
 * 우리 설비가 덧붙인 개수({@code xff-trusted-hops}, 기본 1 = ALB)만큼 건너뛴 자리가 진짜 접속자다.
 *
 * <h3>★ 설비가 바뀌면 이 숫자도 바뀐다</h3>
 * CloudFront 없이 ALB 만이면 0, 앞에 프록시가 하나 더 붙으면 2 다. 코드가 아니라 설정인 이유는
 * 인프라를 옮기는 날 <b>배포 없이</b> 맞출 수 있어야 하기 때문이다(그날 안 맞으면 조용히 틀린다).
 */
@Component
public class ClientIp {

    public static final String HEADER = "X-Forwarded-For";

    /** 우리 설비가 <b>진짜 접속자 뒤에</b> 덧붙인 칸 수. 기본 1 = ALB 한 겹. */
    private final int trustedHops;

    public ClientIp(@Value("${app.zzal.hatch-limits.xff-trusted-hops:1}") int trustedHops) {
        this.trustedHops = Math.max(0, trustedHops);
    }

    /**
     * 이 요청의 접속자 주소. <b>못 알아내면 {@code null}</b> — 그때 IP 상한은 그냥 넘어간다.
     *
     * ★ 못 알아냈을 때 막지 않는 이유 — 로컬·시험·헬스체크처럼 헤더가 없는 정상 호출이 있고,
     *   여기서 막으면 그 전부가 원인 모를 409 가 된다. IP 상한은 완화 장치이고, 진짜 방벽은
     *   사람당·서비스 전체 상한이다(그 둘은 헤더와 무관하게 돈다).
     */
    public String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return fromHeader(request.getHeader(HEADER));
    }

    /** 헤더 문자열 하나에서 뽑는다. 시험이 서버 없이 이 규칙만 따로 확인할 수 있게 갈라 둔다. */
    public String fromHeader(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }
        String[] parts = forwardedFor.split(",");
        // ★ "," 하나만 온 경우 자바는 <b>빈 배열</b>을 준다(뒤쪽 빈 칸을 지운다). 안 막으면
        //   아래 첨자가 배열 밖으로 나가 기록이 아니라 <b>요청이</b> 터진다.
        if (parts.length == 0) {
            return null;
        }
        int index = parts.length - 1 - trustedHops;
        if (index < 0) {
            // 설비가 덧붙였어야 할 칸이 없다 — 줄이 짧은 것이지 사용자가 잘못한 것이 아니다.
            // 남은 것 중 가장 뒤(=가장 우리 쪽에 가까운 것)를 쓴다. 맨 앞으로 내려가지 않는다.
            index = 0;
        }
        String ip = parts[index].trim();
        // 길이를 자른다 — 헤더는 사용자가 길이를 정할 수 있고, 그 문자열이 메모리의 열쇠가 된다.
        if (ip.isEmpty() || ip.length() > 64) {
            return null;
        }
        return ip;
    }
}
