package com.lore.webtoon.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「출석으로 모아서 다음 화 만들기」를 고른 사람에게 보내는 웹푸시(2026-11-03).
 *
 * 작업 알림({@link JobPush})과 같은 구독·같은 보내는 길을 쓴다. 다른 점은 작업이 아니라
 * <b>사람</b>에게 간다는 것 — 그 계정으로 구독한 기기에만 간다. 게스트는 출석이 없어 대상이 아니다.
 *
 * 보내기가 실패해도 예외를 밖으로 내지 않는다. 몇 통이 닿았는지만 돌려준다.
 */
@Service
public class AttendPush {

    private static final Logger log = LoggerFactory.getLogger(AttendPush.class);

    private final PushSubscriptionRepository subs;
    private final PushSender sender;
    private final ObjectMapper json = new ObjectMapper();

    public AttendPush(PushSubscriptionRepository subs, PushSender sender) {
        this.subs = subs;
        this.sender = sender;
    }

    /** 서버에 푸시 키가 있나. 없으면 화면이 구독을 안 받으므로 보낼 데도 없다. */
    public boolean enabled() {
        return sender.enabled();
    }

    /** 이 사람이 알림을 받을 기기가 하나라도 있나. */
    public boolean hasDevice(Long userId) {
        return userId != null && !subs.findByUserId(userId).isEmpty();
    }

    /** 오늘 출석하라는 알림. {@code left} 는 그 화까지 모자란 크레딧. 닿은 기기 수를 돌려준다. */
    public int attend(Long userId, int episode, int reward, int left) {
        return send(userId, Kind.ATTEND, episode, reward, left, "/webtoon?view=event", "attend");
    }

    /** 크레딧이 다 모였다는 알림. 누르면 그 화의 「이어 만들기」로 간다. */
    public int ready(Long userId, int episode, String runId) {
        return send(userId, Kind.READY, episode, 0, 0, "/webtoon?view=next&run=" + runId, "attend-ready");
    }

    private int send(Long userId, Kind kind, int episode, int reward, int left, String url, String tag) {
        if (userId == null || !sender.enabled()) {
            return 0;
        }
        int delivered = 0;
        try {
            List<PushSubscription> to = subs.findByUserId(userId);
            for (PushSubscription sub : to) {
                String[] msg = message(kind, sub.getLang(), episode, reward, left);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("title", msg[0]);
                body.put("body", msg[1]);
                body.put("url", url);
                body.put("tag", tag);
                int code = sender.send(sub, json.writeValueAsString(body), tag);
                if (code == 404 || code == 410) {
                    subs.deleteByEndpoint(sub.getEndpoint());
                } else if (code >= 200 && code < 300) {
                    subs.touch(sub.getId(), Instant.now());
                    delivered++;
                }
            }
        } catch (Exception e) {                     // noqa: 알림 하나 때문에 정리 일꾼이 멈추면 안 된다
            log.warn("출석 알림을 보내지 못했습니다 (user={})", userId, e);
        }
        return delivered;
    }

    enum Kind { ATTEND, READY }

    /** 제목과 본문. 언어는 구독할 때의 화면 언어, 모르면 한국어. */
    static String[] message(Kind kind, String lang, int episode, int reward, int left) {
        String l = PushMessages.normalize(lang);
        String ep = String.valueOf(episode);
        if (kind == Kind.READY) {
            return switch (l) {
                case "en" -> new String[]{"You can make episode " + ep, "You have enough credits now."};
                case "ja" -> new String[]{"第" + ep + "話を作れます", "クレジットが貯まりました。"};
                case "zh" -> new String[]{"可以制作第" + ep + "话了", "积分已经够了。"};
                default -> new String[]{ep + "화를 만들 수 있어요", "크레딧이 다 모였어요."};
            };
        }
        String r = String.valueOf(reward);
        String n = String.valueOf(left);
        return switch (l) {
            case "en" -> new String[]{"Check in today +" + r + "C", n + " credits left until episode " + ep + "."};
            case "ja" -> new String[]{"今日の出席 +" + r + "C", "第" + ep + "話まであと" + n + "クレジット。"};
            case "zh" -> new String[]{"今日签到 +" + r + "C", "距离第" + ep + "话还差" + n + "积分。"};
            default -> new String[]{"오늘 출석 +" + r + "C", ep + "화까지 " + n + "크레딧 남았어요."};
        };
    }
}
