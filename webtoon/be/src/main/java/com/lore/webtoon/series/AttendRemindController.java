package com.lore.webtoon.series;

import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 「출석으로 모아서 다음 화 만들기」 알림(2026-11-03). 로그인한 사람만 — 출석이 계정에 붙는다.
 *
 * 봉투({@code ApiResponse})를 안 씌운다. 웹툰 화면이 부르는 {@code /series/*} 와 같은 모양이다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/attend-remind")
public class AttendRemindController {

    private final AttendRemindService reminds;

    public AttendRemindController(AttendRemindService reminds) {
        this.reminds = reminds;
    }

    @Operation(summary = "출석 알림 상태",
            description = "on(걸려 있나) · run · episode · push(서버가 푸시를 보낼 수 있나) · device(이 계정에 구독한 기기가 있나).")
    @GetMapping
    public Map<String, Object> state() {
        return reminds.state(CreditGate.currentUser());
    }

    @Operation(summary = "출석 알림 켜기",
            description = "다음 화 크레딧이 모자랄 때 「출석으로 모으기」를 고르면 부른다. 날마다 낮 12시에 출석 알림, 다 모이면 한 번 더.")
    @PostMapping
    public Map<String, Object> on(@RequestBody OnRequest req) {
        return reminds.on(CreditGate.currentUser(), req.run(), req.episode() == null ? 0 : req.episode(),
                req.cost() == null ? 0 : req.cost());
    }

    @Operation(summary = "출석 알림 끄기")
    @DeleteMapping
    public Map<String, Object> off() {
        return reminds.off(CreditGate.currentUser());
    }

    public record OnRequest(String run, Integer episode, Integer cost) {
    }
}
