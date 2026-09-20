package com.lore.webtoon.job;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 웹툰 쪽 시각 트리거를 켜는 자리.
 *
 * <h2>왜 여기에 또 있나</h2>
 *
 * {@code @EnableScheduling} 은 zzal 에도 있다({@code ZzalSchedulingConfig}).
 * 한 앱에 두 번 붙어도 탈이 없고(같은 후처리기 하나가 등록될 뿐이다),
 * <b>남의 도메인 파일에 기대지 않으려고</b> 따로 둔다 — 그 파일이 지워지거나
 * 조건이 붙는 순간 이쪽 정리 작업이 아무 말 없이 안 돌게 된다. 조용히 안 도는
 * 정리 작업은 디스크가 찰 때까지 아무도 모른다.
 *
 * <h2>지금 걸려 있는 것</h2>
 *
 * {@link RunFiles#sweepStale()} 하나뿐이다 — 매일 새벽 4시 30분에 끝까지 못
 * 간 작품 폴더를 치운다. 다 만든 작품의 그림은 시각과 무관하게 S3 에 올린
 * 그 자리에서 바로 치운다({@link RunFiles#sweepUploaded}).
 */
@Configuration
@EnableScheduling
public class WebtoonSchedulingConfig {
}
