package com.lore.zzal.generation.client;

import java.util.List;

/**
 * 격자를 잘라 기본 행동 webp 를 만든다.
 *
 * ★ 구현이 둘 — {@code FakePostProcessor}(지금, 실제로 안 자름) · {@code PythonPostProcessor}(검증된 파이썬 실행).
 * ★ <b>버전을 세션마다 받는다</b> — 빈이 만들어질 때의 설정 버전이 아니라 <b>그 job 의 버전</b>(폴백으로 v1 이 됐을 수
 *   있다, #218 리뷰)으로 스크립트와 출력 이름을 고른다.
 *
 * <h3>★★ 왜 세션인가 — 1층과 2층이 <b>같은 작업 폴더</b>를 써야 한다</h3>
 * v4 후처리는 그림 옆에 {@code anchors.json} 을 같이 낸다. 1층 호출이 그 파일을 만들고 2층 호출이
 * <b>그 파일에 합쳐 쓴다</b>(중간 상태 파일 없이 매번 완전한 JSON 을 덮어쓴다). 그러려면 두 호출이
 * 같은 폴더를 봐야 하는데, 전에는 자르는 메서드가 <b>자기 안에서</b> 임시 폴더를 만들고
 * {@code finally} 로 지웠다 — 폴더를 공유하려 해도 <b>1층이 지운 폴더를 2층이 보게</b> 된다.
 * 그래서 폴더의 수명을 두 호출 바깥({@code PostProcessStep})으로 올린다.
 *
 * <h3>★ 세션은 닫아야 끝난다</h3>
 * {@link Session#close()} 가 앵커를 <b>한 번만</b> 올리고 폴더를 지운다. 층마다 올리면 1층이 올린 것을
 * 2층이 덮어쓰는데, 그 사이에 2층이 실패하면 <b>반쪽 앵커</b>가 올라간 채로 남는다.
 */
public interface PostProcessor {

    /**
     * 한 부화의 후처리를 시작한다. 1층·2층은 이 세션 안에서 <b>같은 스레드로 순차</b>로 돈다.
     *
     * @param outputPrefix 결과를 올릴 폴더({@code images/zzal/pets/{id}/basic/{판}})
     * @param version      그 job 의 파이프라인 버전
     */
    Session open(String outputPrefix, String version) throws Exception;

    /** 한 부화의 후처리 한 묶음. 두 층이 이 안에서 작업 폴더를 공유한다. */
    interface Session extends AutoCloseable {

        /**
         * v1 — 격자 1장 → 8상태. 출력 이름은 설정 {@code app.zzal.hatch.states.{version}}.
         *
         * @param gridImageKey 4x4 격자의 S3 키
         */
        void split(String gridImageKey) throws Exception;

        /**
         * v2 — 격자 한 장을 <b>카탈로그 key 이름</b>으로 자른다(8개). 파이썬에 {@code --keys} 로 넘긴다.
         * 출력 = {@code {outputPrefix}/{key}.webp}. 두 장(grid·grid2)이면 두 번 부른다.
         */
        void split(String gridImageKey, List<String> keys) throws Exception;

        /**
         * v4 — 위와 같되 <b>칸의 자세 유형</b>을 함께 넘긴다({@code --postures}).
         *
         * ★ 왜 따로인가 — 후처리는 서 있는 칸과 앉은·누운 칸을 다르게 정렬한다. 어느 칸이 어느 자세인지는
         *   층마다 다르고(1층은 sick·sleep, 2층은 wash), 그걸 안 넘기면 파이썬이 <b>1층 기본값</b>으로
         *   되돌아가 2층의 reply·wake_up 을 앉기·눕기로 맞춘다. 통과는 하고 그림만 조용히 틀어진다.
         * ★ v1·v2 의 후처리 스크립트는 이 인자를 모른다 — 그래서 빈 문자열이면 안 넘긴다.
         *
         * @param postures {@code "base=standing,...,sick=crouch,...,sleep=lying"}. 이름은 {@code keys} 의 것
         */
        void split(String gridImageKey, List<String> keys, String postures) throws Exception;

        /**
         * 앵커를 올리고(있으면) 작업 폴더를 지운다.
         *
         * ★ 앵커를 내야 하는 버전인데 없으면 <b>여기서 실패시킨다.</b> 그림만 올라가고 앵커만 사라진
         *   모양은 서버에 아무 오류가 없어 화면을 봐야만 드러난다.
         */
        @Override
        void close() throws Exception;
    }
}
