package com.lore.zzal.it;

import com.lore.zzal.pet.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@ZzalIntegrationTest
class ReturningGuideIT extends ZzalItSupport {
    record Room(Long user, Long pet) { String path() { return "/api/zzal/v1/me/pets/" + pet; } }
    private Room room(Long user, Integer version, boolean complete) {
        Instant now = kstToday(10,0);
        Long id = transactions.execute(tx -> {
            var pet = ZzalPet.draft(user,newUploadedImageKey(user),now);
            pet.character("기존아이","기존메모",List.of(Personality.GENTLE),"현대","존댓말","일상",now);
            pet.markAlive("sheet","identity",now);
            if (version == null) { if (complete) pet.skipTutorial(now); }
            else pet.devSetFirstIntro(version,complete ? FirstIntro.Step.COMPLETE : FirstIntro.Step.FEED,now);
            return petRepository.save(pet).getId();
        });
        pinClock(id,now);return new Room(user,id);
    }
    private com.fasterxml.jackson.databind.JsonNode read(Room r) throws Exception {
        return getJson(r.user,r.path()+"/returning-guide").path("data");
    }
    private org.springframework.test.web.servlet.MvcResult advance(Room r, Object req) throws Exception {
        return postAs(r.user,r.path()+"/returning-guide",req);
    }
    private String hash(Room r) throws Exception { return getJson(r.user,r.path()+"/persona").path("data").path("personaSourceHash").asText(); }

    @Test void legacyEligibilityAndNewUserExclusionDoNotWriteOnRead() throws Exception {
        var old=room(newUserId(),null,true);assertThat(read(old).path("eligible").asBoolean()).isTrue();
        var v3=room(newUserId(),3,true);assertThat(read(v3).path("eligible").asBoolean()).isTrue();
        var modern=room(newUserId(),4,true);assertThat(read(modern).path("eligible").asBoolean()).isFalse();
        var unfinished=room(newUserId(),3,false);assertThat(read(unfinished).path("eligible").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from zzal_returning_guide",Long.class)).isZero();
        assertThat(advance(modern,Map.of("step",0)).getResponse().getStatus()).isEqualTo(400);
    }
    @Test void progressSurvivesRequestsAndCannotMoveBackwards() throws Exception {
        var r=room(newUserId(),3,true);assertThat(read(r).path("step").asInt()).isZero();
        assertThat(advance(r,Map.of("step",3)).getResponse().getStatus()).isEqualTo(200);
        assertThat(advance(r,Map.of("step",1)).getResponse().getStatus()).isEqualTo(200);
        assertThat(read(r).path("step").asInt()).isEqualTo(3);
        assertThat(read(r).path("completed").asBoolean()).isFalse();
        assertThat(advance(r,Map.of("step",6)).getResponse().getStatus()).isEqualTo(400);
        assertThat(advance(r,Map.of("complete",true)).getResponse().getStatus()).isEqualTo(400);
    }
    @Test void completionRequiresSettingsStageAndCurrentPersonaThenIsIdempotent() throws Exception {
        var r=room(newUserId(),3,true);String initial=hash(r);
        assertThat(advance(r,Map.of("step",5,"complete",true,"expectedPersonaHash",initial)).getResponse().getStatus()).isEqualTo(400);
        assertThat(advance(r,Map.of("step",5)).getResponse().getStatus()).isEqualTo(200);
        assertThat(advance(r,Map.of("step",5,"complete",true)).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(r.user,r.path()+"/personality",Map.of("personality","COOL","world","새로운세계")).getResponse().getStatus()).isEqualTo(200);
        assertThat(advance(r,Map.of("step",5,"complete",true,"expectedPersonaHash",initial)).getResponse().getStatus()).isEqualTo(409);
        assertThat(read(r).path("completed").asBoolean()).isFalse();
        assertThat(advance(r,Map.of("step",5,"complete",true,"expectedPersonaHash",hash(r))).getResponse().getStatus()).isEqualTo(200);
        assertThat(read(r).path("completed").asBoolean()).isTrue();
        assertThat(advance(r,Map.of("step",0)).getResponse().getStatus()).isEqualTo(200);
        assertThat(read(r).path("step").asInt()).isEqualTo(5);
    }
    @Test void onceIsAccountScopedAndAnotherOwnedEligiblePetCanFinish() throws Exception {
        Long owner=newUserId();var a=room(owner,3,true);var b=room(owner,null,true);
        advance(a,Map.of("step",5));assertThat(read(b).path("boundPetId").asLong()).isEqualTo(a.pet);
        assertThat(advance(b,Map.of("step",5,"complete",true,"expectedPersonaHash",hash(b))).getResponse().getStatus()).isEqualTo(200);
        assertThat(read(a).path("completed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from zzal_returning_guide where user_id=?",Long.class,owner)).isEqualTo(1);
        var other=room(newUserId(),3,true);assertThat(read(other).path("completed").asBoolean()).isFalse();
    }
    @Test void foreignPetCannotReadOrWriteAnotherAccountsReceipt() throws Exception {
        var r=room(newUserId(),3,true);Long stranger=newUserId();
        assertThat(postAs(stranger,r.path()+"/returning-guide",Map.of("step",5)).getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(r.path()+"/returning-guide").with(asUser(stranger))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(r.path()+"/returning-guide")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from zzal_returning_guide",Long.class)).isZero();
    }
    @Test void lifecycleAndExistingSourceRemainUnchanged() throws Exception {
        var r=room(newUserId(),3,true);var before=petRepository.findById(r.pet).orElseThrow();
        String source=hash(r);advance(r,Map.of("step",5));advance(r,Map.of("step",5,"complete",true,"expectedPersonaHash",source));
        var after=petRepository.findById(r.pet).orElseThrow();
        assertThat(after.getFirstIntroStep()).isEqualTo(before.getFirstIntroStep());
        assertThat(after.getTutorialStep()).isEqualTo(before.getTutorialStep());
        assertThat(after.getClockStartedAt()).isEqualTo(before.getClockStartedAt());
        assertThat(hash(r)).isEqualTo(source);
    }
}
