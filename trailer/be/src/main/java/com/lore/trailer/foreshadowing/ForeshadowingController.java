package com.lore.trailer.foreshadowing;

import com.lore.common.response.ApiResponse;
import com.lore.trailer.foreshadowing.dto.ForeshadowingResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 복선 카드 API 셋 — Trailer 탭(Piece Maker)이 카드를 받는 자리.
 *
 * <p>명세의 정본은 NarrativeAnalysis 저장소의 {@code migration/front_back_protocol.md} 2-1~2-4 다.
 * 요약: 장부 정보 · 카드 목록과 검색 · 카드 상세. 모두 GET 이고 로그인이 없다.
 *
 * <h3>★ 주소에 {@code public} 이 들어가는 이유</h3>
 * 시큐리티가 {@code GET /api/trailer/v1/public/**} 만 로그인 없이 연다(zzal 의 공유 링크와 같은 방식).
 * 운영에서도 열려 있다 — 원작을 요약한 글 1,651건을 요청 열일곱 번으로 다 받아 갈 수 있다는 뜻이다.
 * 그렇게 두기로 했다(decisions.md 1-15).
 *
 * <h3>★ 회차 N 이 모든 카드 조회에 붙는다</h3>
 * 독자가 읽은 회차다. 서버는 N화 이하에 심은 카드만 주고, N화 뒤에 회수된 복선은 미회수로 가려서 준다.
 * DB 에는 원래 값이 있고 꺼낼 때 가린다 — 화면이 가리면 가리기 전 값이 브라우저까지 온다.
 *
 * <h3>★ 회차 · 쪽 · 크기는 글자로 받는다</h3>
 * {@code int} 로 받으면 {@code ?chapter=abc} 가 500 이다. {@link QueryParams} 가 읽고 400 을 던진다.
 */
@Tag(name = "복선 카드(공개)", description = "Trailer 탭이 카드를 받는 조회 API. 인증이 필요 없다")
@RestController
@RequestMapping("/api/trailer/v1/public/cards")
public class ForeshadowingController {

    private final ForeshadowingService service;

    public ForeshadowingController(ForeshadowingService service) {
        this.service = service;
    }

    @Operation(summary = "장부 정보", description = """
            화면을 열 때 한 번 부른다. 가장 뒤 회차, 해시 둘, 유형 다섯, 권하는 인물 다섯을 준다.

            카드를 50장씩 나눠 받으면 첫 쪽에 유형과 인물이 다 없고, 해시는 첫 쪽을 받기 전에 있어야
            브라우저 저장 키를 만들 수 있다. 그래서 카드와 상관없는 값을 따로 준다.

            카드 표가 비어 있으면 503(TRAILER_LEDGER_NOT_LOADED). 운영 DB 에 카드 SQL 을 넣기 전이다.""")
    @GetMapping("/meta")
    public ApiResponse<ForeshadowingResponses.Meta> meta() {
        return ApiResponse.ok(service.meta());
    }

    @Operation(summary = "카드 목록과 검색", description = """
            독자가 읽은 회차 N 이하에 심은 카드를 표의 id 순(= T 번호 순)으로 나눠 준다.
            N화 뒤에 회수된 복선은 status=open, resolvedChapter=null, resolution=null 로 가려서 준다.

            검색 규칙은 화면이 브라우저에서 찾던 것과 같다.
            · search 가 T12 꼴이면 그 번호의 카드 한 장만(대소문자 무관)
            · 그 밖에는 빈칸으로 나눈 단어가 모두 카드의 글(id · 제목 · 본문 · 인물 · 유형 · "12화")에 들어 있어야 한다.
              소문자로 바꾸고 빈칸을 없애고 견준다. % 와 _ 는 글자 그대로 찾는다
            · kind 는 유형의 한국어 이름이 똑같은 카드만. 검색어와 함께 건다

            chapter 가 없거나 숫자가 아니거나 1~maxChapter 를 벗어나면 400(TRAILER_INVALID_CHAPTER).
            search 가 200자 또는 단어 10개를 넘거나 page · size 가 틀리면 400(INVALID_INPUT).""")
    @GetMapping
    public ApiResponse<ForeshadowingResponses.CardPage> list(
            @Parameter(description = "독자가 읽은 회차 N. 1부터 maxChapter 까지", required = true, example = "200",
                    schema = @Schema(type = "integer"))
            @RequestParam(required = false) String chapter,
            @Parameter(description = "검색어. 200자 · 단어 10개까지. 비면 N화 카드 전부", example = "밀짚모자")
            @RequestParam(required = false) String search,
            @Parameter(description = "유형의 한국어 이름. 비면 거르지 않는다", example = "약속")
            @RequestParam(required = false) String kind,
            @Parameter(description = "쪽. 0부터. 기본 0", example = "0", schema = @Schema(type = "integer"))
            @RequestParam(required = false) String page,
            @Parameter(description = "한 쪽의 크기. 기본 50, 최대 100", example = "50", schema = @Schema(type = "integer"))
            @RequestParam(required = false) String size) {
        return ApiResponse.ok(service.list(
                QueryParams.chapter(chapter),
                SearchTerms.parse(search),
                kind,
                QueryParams.page(page),
                QueryParams.size(size)));
    }

    @Operation(summary = "카드 상세", description = """
            카드 한 장을 번호로 연다. 게시글에 적힌 번호로 카드를 열 때 부른다.
            목록이 준 카드에는 상세의 칸이 다 있어서, 목록에서 여는 상세는 이 API 를 부르지 않는다.

            회수 칸 셋은 목록과 같은 규칙으로 N 기준으로 가린다.
            모르는 번호와 N화 뒤에 심은 카드는 같은 404(TRAILER_CARD_NOT_FOUND)다 — 구분해 주면
            번호를 바꿔 가며 뒤 회차의 카드가 있는지 알아낼 수 있다.""")
    @GetMapping("/{id}")
    public ApiResponse<ForeshadowingResponses.Card> detail(
            @Parameter(description = "카드 번호", example = "T12")
            @PathVariable String id,
            @Parameter(description = "독자가 읽은 회차 N. 1부터 maxChapter 까지", required = true, example = "200",
                    schema = @Schema(type = "integer"))
            @RequestParam(required = false) String chapter) {
        return ApiResponse.ok(service.detail(id, QueryParams.chapter(chapter)));
    }
}
