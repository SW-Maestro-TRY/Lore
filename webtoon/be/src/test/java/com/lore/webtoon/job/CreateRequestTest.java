package com.lore.webtoon.job;

import com.lore.webtoon.art.WebtoonPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면이 보내는 본문을 그대로 읽는가.
 *
 * <b>이 파일이 지키는 것</b>: 만들기 폼의 칸 이름. 화면은 프로토타입에서 옮겨
 * 온 것이라 파이썬이 받던 이름({@code agree_ip} · {@code photos_data})으로
 * 보내는데, 자바 쪽만 camelCase 로 적어 두면 그 칸이 통째로 안 들어온다.
 *
 * 실제로 그렇게 막혔다 — 저작권에 동의하고 눌러도 "동의해야 만들 수 있습니다"
 * 가 뜨고, 캐릭터 사진도 같이 버려졌다. 화면에는 사유조차 안 보였다(본문이
 * 통째로 거절돼서 "입력값이 올바르지 않습니다" 만 남았다).
 */
class CreateRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 화면(webtoon/fe 의 WebtoonPage.start)이 실제로 보내는 그대로. */
    private static final String FROM_SCREEN = """
            {"name":"차사","character":"택배 배달 저승사자","photo_note":"","fields":{},
             "genre":"일상","story":"오늘도 못 부친 택배를 배달한다","style":"romance",
             "photos_data":["ZmFrZQ=="],"agree_ip":true,"checkpoints":false,"uid":"u1"}""";

    @Test
    @DisplayName("화면이 보내는 이름 그대로 들어온다")
    void readsScreenNames() throws Exception {
        JobService.CreateRequest form = JSON.readValue(FROM_SCREEN, JobService.CreateRequest.class);

        assertThat(form.agreeIp()).isTrue();
        assertThat(form.photosData()).containsExactly("ZmFrZQ==");
        assertThat(form.name()).isEqualTo("차사");
        assertThat(form.style()).isEqualTo("romance");
        assertThat(form.checkpoints()).isFalse();
    }

    @Test
    @DisplayName("사진에 붙인 한 마디도 받는다 — 안 받으면 그 말이 어디에도 안 닿는다")
    void readsPhotoNote() throws Exception {
        JobService.CreateRequest form = JSON.readValue("""
                {"name":"차사","photo_note":"왼쪽이 주인공이에요","agree_ip":true}""",
                JobService.CreateRequest.class);

        assertThat(form.photoNote()).isEqualTo("왼쪽이 주인공이에요");
    }

    @Test
    @DisplayName("자바 식 이름으로 보내도 받는다")
    void alsoReadsJavaNames() throws Exception {
        JobService.CreateRequest form = JSON.readValue(
                """
                {"name":"차사","agreeIp":true,"photosData":["ZmFrZQ=="]}""",
                JobService.CreateRequest.class);

        assertThat(form.agreeIp()).isTrue();
        assertThat(form.photosData()).containsExactly("ZmFrZQ==");
    }

    /**
     * 동의 칸이 아예 없을 때. {@code boolean} 이면 Jackson 이 여기서 본문을
     * 통째로 거절해 버려서, 사람은 <b>무엇을 고쳐야 하는지 모르는</b> 400 만
     * 받는다. 안 한 것으로 읽고, 왜 안 되는지는 뒤에서 한글로 말하게 둔다.
     */
    @Test
    @DisplayName("동의 칸이 없으면 안 한 것으로 읽는다 — 본문을 거절하지 않는다")
    void missingAgreeIsFalse() throws Exception {
        JobService.CreateRequest form = JSON.readValue(
                "{\"name\":\"차사\"}", JobService.CreateRequest.class);

        assertThat(form.agreeIp()).isFalse();
        assertThat(form.photosData()).isNull();
    }
}
