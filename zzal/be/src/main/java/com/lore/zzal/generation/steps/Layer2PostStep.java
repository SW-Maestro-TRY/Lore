package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.HatchPostures;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionImageKeys;
import com.lore.zzal.motion.MotionLayer;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.stereotype.Component;

/**
 * 2층 격자를 잘라 8종 webp 로 만든다 — 부화가 1층으로 끝난 <b>뒤에</b> 도는 2층 작업의 마지막 단계(#696).
 *
 * <h3>★★ 새 판을 연다 — 1층 판에 덧올리지 않는다</h3>
 * 주소에 판이 들어가고 CDN 이 1년 캐시를 든다. 1층 판의 {@code anchors.json} 은 부화 직후 화면이 이미
 * 받아 갔으므로(1층 소품 자리), 같은 주소에 2층을 합친 파일을 덮어써도 <b>화면은 옛 파일을 계속 본다.</b>
 * 그래서 판을 하나 올리고, 1층 8종을 그대로 옮겨 실은 뒤(바이트 불변) 2층 8종과 합친 앵커를 함께 둔다.
 *
 * <h3>★ 1층 앵커를 먼저 내려받는다</h3>
 * 후처리 스크립트는 출력 폴더에 이미 있는 {@code anchors.json} 에서 1층 K·Hw 를 받아 2층을 정규화하고
 * 같은 파일에 합쳐 쓴다(옛 흐름에서 1층이 같은 세션에서 남기던 그 파일). 같은 파일을 같은 자리에 두므로
 * 결과 바이트가 전과 같다.
 *
 * <h3>★ 판을 "올렸다" 고 적는 것은 여기가 아니다</h3>
 * 산출물로 <b>판 번호</b>를 글자로 돌려준다. 판을 올리고 READY 로 바꾸는 일은 {@code Layer2Service} 가
 * 한 트랜잭션에서 한다 — 판만 먼저 올라가면 2층이 안 열린 채 새 주소를 받는 짧은 틈이 생긴다(해롭진 않지만 갈라 둘 이유도 없다).
 * 재시도는 성공한 단계를 건너뛰므로, 이 단계가 성공하고 마무리 전에 서버가 죽어도 다음 시도가 그 판 번호를 이어받는다.
 */
@Component
public class Layer2PostStep implements GenerationStep {

    public static final String NAME = "postprocess2";

    private final PostProcessor postProcessor;
    private final MotionCatalog catalog;
    private final HatchPostures postures;
    private final ZzalPetRepository petRepository;

    public Layer2PostStep(PostProcessor postProcessor, MotionCatalog catalog, HatchPostures postures,
                          ZzalPetRepository petRepository) {
        this.postProcessor = postProcessor;
        this.catalog = catalog;
        this.postures = postures;
        this.petRepository = petRepository;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 1층 후처리와 같은 값(S3 왕복이 대부분이다 — PostProcessStep 주석). 1층 8종 옮겨 싣기가 더해진다. */
    @Override
    public int limitSeconds() {
        return 120;
    }

    @Override
    public String label() {
        return "새 동작을 연습하는 중";
    }

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String grid2 = ctx.image(PostProcessStep.GRID2);
        if (grid2 == null) {
            throw new IllegalStateException("2층 격자(%s)가 없습니다".formatted(PostProcessStep.GRID2));
        }
        ZzalPet pet = petRepository.findById(ctx.petId())
                .orElseThrow(() -> new IllegalStateException("펫이 없습니다: " + ctx.petId()));
        int from = pet.getBasicRound();
        if (from <= 0) {
            // 1층이 판 번호 규약으로 구워져 있어야 앵커·그림을 옮겨 실을 수 있다.
            throw new IllegalStateException("1층 판이 없습니다(basicRound=0) — 1층부터 구워야 합니다");
        }
        int round = from + 1;
        String prefix = MotionImageKeys.basicPrefix(ctx.petId(), round);
        try (PostProcessor.Session session = postProcessor.open(prefix, ctx.version())) {
            session.seedAnchors(MotionImageKeys.anchors(ctx.petId(), from));
            session.carryOver(MotionImageKeys.basicPrefix(ctx.petId(), from),
                    PostProcessStep.keysOf(catalog, MotionLayer.BASIC_1));
            PostProcessStep.splitTagged(session, PostProcessStep.GRID2, grid2, MotionLayer.BASIC_2,
                    ctx.version(), PostProcessStep.keysOf(catalog, MotionLayer.BASIC_2), postures);
        }
        return StepResult.text(NAME, String.valueOf(round), null, java.math.BigDecimal.ZERO);
    }
}
