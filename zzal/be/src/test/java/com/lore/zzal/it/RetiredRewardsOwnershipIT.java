package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.Layer2Status;
import com.lore.zzal.motion.ZzalMotionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@ZzalIntegrationTest
class RetiredRewardsOwnershipIT extends ZzalItSupport {
    @Autowired ZzalMotionRepository motions;

    private ZzalPet pet(Long owner, int mask) {
        Instant at=kstToday(11,0);
        ZzalPet p=transactions.execute(tx->{
            ZzalPet made=petRepository.save(ZzalPet.draft(owner,newUploadedImageKey(owner),at));
            made.character("fixture",null,null,null,null,null,at);
            made.markAlive("images/fixture/sheet.png","fixture identity",at);
            made.skipTutorial(at);
            ReflectionTestUtils.setField(made,"layer2Status",Layer2Status.READY);
            ReflectionTestUtils.setField(made,"basicRound",2);
            ReflectionTestUtils.setField(made,"legacyLayer2OwnedMask",mask);
            for(String c:new String[]{"feeds","snacks","cleans","bathCount","chatAnswers","pets","gameStarts","wakes"})
                ReflectionTestUtils.setField(made,c,99);
            return made;
        });
        motionSeeder.seed(p.getId(),at);pinClock(p.getId(),at);return p;
    }
    private JsonNode motion(JsonNode detail,int seq){
        for(JsonNode m:detail.path("motions"))if(m.path("seq").asInt()==seq)return m;
        throw new AssertionError("Missing motion "+seq);
    }
    @Test void oldFrozenOwnershipSurvivesDetailAlbumAndRepeatedReads() throws Exception {
        Long owner=newUserId();ZzalPet p=pet(owner,3);
        for(int attempt=0;attempt<2;attempt++){
            JsonNode detail=getJson(owner,"/api/zzal/v1/me/pets/"+p.getId()).path("data");
            assertThat(motion(detail,9).path("unlocked").asBoolean()).isTrue();
            assertThat(motion(detail,9).path("basicImageKey").asText()).endsWith("/basic/2/eat_rice.webp");
            assertThat(motion(detail,10).path("unlocked").asBoolean()).isTrue();
            assertThat(motion(detail,11).path("unlocked").asBoolean()).isFalse();
            assertThat(motion(detail,11).path("basicImageKey").isNull()).isTrue();
            assertThat(motion(detail,11).path("hint").isNull()).isTrue();
        }
        JsonNode album=getJson(owner,"/api/zzal/v1/me/pets/"+p.getId()+"/album").path("data");
        assertThat(motion(album,9).path("unlocked").asBoolean()).isTrue();
        assertThat(petRepository.findById(p.getId()).orElseThrow().ownsRetiredLayerTwo(11)).isFalse();
    }
    @Test void temporarilyPendingLayerDoesNotHideAnAlreadyOwnedMotion() throws Exception {
        Long owner=newUserId();ZzalPet p=pet(owner,1);
        transactions.executeWithoutResult(tx->{
            var saved=petRepository.findById(p.getId()).orElseThrow();
            ReflectionTestUtils.setField(saved,"layer2Status",Layer2Status.PENDING);
            ReflectionTestUtils.setField(saved,"layer2Flagged",true);
        });
        JsonNode detail=getJson(owner,"/api/zzal/v1/me/pets/"+p.getId()).path("data");
        assertThat(motion(detail,9).path("unlocked").asBoolean()).isTrue();
        assertThat(motion(detail,9).path("basicImageKey").asText()).endsWith("/basic/2/eat_rice.webp");
        assertThat(motion(detail,10).path("unlocked").asBoolean()).isFalse();
    }
    @Test void readyAndLargeCountersCannotMintAnyNewLayerTwoOwnership() throws Exception {
        Long owner=newUserId();ZzalPet p=pet(owner,0);
        JsonNode detail=getJson(owner,"/api/zzal/v1/me/pets/"+p.getId()).path("data");
        for(int seq=9;seq<=16;seq++){
            assertThat(motion(detail,seq).path("unlocked").asBoolean()).isFalse();
            assertThat(motion(detail,seq).path("basicImageKey").isNull()).isTrue();
        }
        assertThat(motion(detail,1).path("unlocked").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from zzal_gen_job",Long.class)).isZero();
    }
    @Test void alreadyRevealedGiftIsStillReadableButPendingGiftDoesNotArrive() throws Exception {
        Long owner=newUserId();ZzalPet p=pet(owner,0);Instant at=kstToday(10,0);
        transactions.executeWithoutResult(tx->{
            var rows=motions.findByPetIdOrderBySeqAsc(p.getId());
            for(var m:rows)if(m.getSeq()==101||m.getSeq()==102){
                ReflectionTestUtils.setField(m,"imageKey","images/fixture/gift-"+m.getSeq()+".webp");
                m.approve(at);if(m.getSeq()==101)m.reveal(at);
            }
        });
        JsonNode detail=getJson(owner,"/api/zzal/v1/me/pets/"+p.getId()).path("data");
        assertThat(motion(detail,101).path("advanced").path("imageKey").asText()).endsWith("gift-101.webp");
        assertThat(motion(detail,102).path("advanced").path("imageKey").isNull()).isTrue();
        assertThat(motions.findByPetIdOrderBySeqAsc(p.getId()).stream().filter(m->m.getSeq()==102).findFirst().orElseThrow().isRevealed()).isFalse();
    }
    @Autowired com.lore.zzal.motion.MotionCatalog catalog;
    @Test void preservedBasicAndGiftStillIssueAndOpenPublicShareWhileUnownedIsRejected() throws Exception {
        Long owner=newUserId();ZzalPet p=pet(owner,1);Instant at=kstToday(10,0);
        transactions.executeWithoutResult(tx->{
            var gift=motions.findByPetIdAndSeq(p.getId(),101).orElseThrow();
            ReflectionTestUtils.setField(gift,"imageKey","images/fixture/gift-101.webp");
            gift.approve(at);gift.reveal(at);
        });
        for(int seq:new int[]{9,101}){
            String key=catalog.bySeq(seq).orElseThrow().key();
            var first=postAs(owner,"/api/zzal/v1/me/pets/"+p.getId()+"/share",java.util.Map.of("motionKey",key,"kind","SHARE"));
            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            String token=body(first).path("data").path("token").asText();
            var again=postAs(owner,"/api/zzal/v1/me/pets/"+p.getId()+"/share",java.util.Map.of("motionKey",key,"kind","SHARE"));
            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(again).path("data").path("token").asText()).isEqualTo(token);
            var opened=mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/zzal/v1/public/share/"+token)).andReturn();
            assertThat(opened.getResponse().getStatus()).isEqualTo(200);
            String image=body(opened).path("data").path("imageKey").asText();
            assertThat(image).endsWith(seq==9?"/basic/2/eat_rice.webp":"gift-101.webp");
        }
        var denied=postAs(owner,"/api/zzal/v1/me/pets/"+p.getId()+"/share",java.util.Map.of("motionKey",catalog.bySeq(10).orElseThrow().key(),"kind","SHARE"));
        assertThat(denied.getResponse().getStatus()).isEqualTo(409);
    }

}
