package com.lore.zzal.it;
import com.lore.zzal.pet.*;
import com.lore.zzal.pet.persona.*;
import com.lore.zzal.chat.persona.PersonaSheetBuilder;
import com.lore.zzal.chat.prompt.PromptAssembler;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@ZzalIntegrationTest
class PersonaEditIT extends ZzalItSupport {
    record Room(Long user,Long pet){String path(){return "/api/zzal/v1/me/pets/"+pet;}}
    private Room room(){Long user=newUserId();Instant at=kstToday(10,0);
        Long id=transactions.execute(tx->{var p=ZzalPet.draft(user,newUploadedImageKey(user),at);
            p.character("고유캐릭터","  기존 메모\n보존  ",List.of(Personality.GENTLE,Personality.SHY),"옛세계","조용한 존댓말","일상 판타지",at);p.markAlive("sheet","identity",at);p.skipTutorial(at);
            p.rememberExplicitCallMe("민지");return petRepository.save(p).getId();});pinClock(id,at);return new Room(user,id);}
    private String hash(Room r)throws Exception{return getJson(r.user,r.path()+"/persona").path("data").path("personaSourceHash").asText();}
    private AuthoredPersona source(String raw){return new AuthoredPersona(1,List.of(
        new AuthoredPersona.Field("persona",List.of("다정한","장난기있는","차분한"),raw,false),
        new AuthoredPersona.Field("world",List.of("현대"),"악마가 운영하는 작은 공방",false),
        new AuthoredPersona.Field("extra",List.of(),"",true)));}
    @Test void fullSourceAndExplicitAddressEditReachChatWithoutChangingIdentityOrClock()throws Exception{
        var r=room();var before=petRepository.findById(r.pet).orElseThrow();Instant clock=before.getClockStartedAt();
        var raw="나무냄새가 나는 공방에서 작은 물건을 만든다. ".repeat(30)+"나는 우동이 아닌 짜장면을 좋아하는 설정이다.";
        var request=Map.of("authoredPersona",source(raw),"callMe","수아","expectedPersonaHash",hash(r));
        var result=postAs(r.user,r.path()+"/personality",request);assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var stored=petRepository.findById(r.pet).orElseThrow();assertThat(stored.getAuthoredPersona()).isEqualTo(source(raw));
        assertThat(stored.getCallMe()).isEqualTo("수아");assertThat(stored.getName()).isEqualTo("고유캐릭터");
        assertThat(stored.getClockStartedAt()).isEqualTo(clock);assertThat(stored.getFirstIntroStep()).isNull();
        var prompt=PromptAssembler.system(new PersonaSheetBuilder(null).build(stored));
        assertThat(prompt).contains("짜장면", "악마가 운영하는 작은 공방","상대를 부르는 말: 수아");
        assertThat(getJson(r.user,r.path()+"/persona").path("data").path("authoredPersona").path("fields").get(0).path("rawText").asText()).isEqualTo(raw);
        assertThat(postAs(r.user,r.path()+"/personality",request).getResponse().getStatus()).isEqualTo(200);
    }
    @Test void staleEditCannotOverwriteNewerPersonaOrVoluntaryAddressCorrection()throws Exception{
        var r=room();var old=hash(r);assertThat(postAs(r.user,r.path()+"/personality",Map.of("authoredPersona",source("첫 수정"),"expectedPersonaHash",old)).getResponse().getStatus()).isEqualTo(200);
        var conflict=postAs(r.user,r.path()+"/personality",Map.of("authoredPersona",source("낡은 수정"),"callMe","옛이름","expectedPersonaHash",old));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);assertThat(body(conflict).path("error").path("code").asText()).isEqualTo("ZZAL_PERSONA_SOURCE_STALE");
        var p=petRepository.findById(r.pet).orElseThrow();assertThat(p.getAuthoredPersona()).isEqualTo(source("첫 수정"));assertThat(p.getCallMe()).isEqualTo("민지");
    }
    @Test void missingHashBlankAddressAndForeignOwnerNeverChangeSource()throws Exception{
        var r=room();assertThat(postAs(r.user,r.path()+"/personality",Map.of("authoredPersona",source("x"))).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(r.user,r.path()+"/personality",Map.of("callMe"," ","expectedPersonaHash",hash(r))).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(newUserId(),r.path()+"/personality",Map.of("callMe","누구","expectedPersonaHash",hash(r))).getResponse().getStatus()).isEqualTo(404);
        assertThat(petRepository.findById(r.pet).orElseThrow().getCallMe()).isEqualTo("민지");
    }
    @Test void legacyReadbackAndPartialEditorConversionPreservePromptInputsAndOriginalColumns()throws Exception {
        var r=room();var response=getJson(r.user,r.path()+"/persona").path("data");
        var legacy=response.path("legacySource");
        assertThat(response.path("authoredPersona").isNull()).isTrue();
        assertThat(legacy.path("personalities").toString()).isEqualTo("[\"GENTLE\",\"SHY\"]");
        assertThat(legacy.path("note").asText()).isEqualTo("  기존 메모\n보존  ");
        assertThat(legacy.path("tone").asText()).isEqualTo("조용한 존댓말");
        assertThat(legacy.path("genre").asText()).isEqualTo("일상 판타지");
        var before=petRepository.findById(r.pet).orElseThrow();
        String frozenHash=PersonaSourceVersion.of(before);var clock=before.getClockStartedAt();
        // Exact legacy editor preload plus one new answer; no inferred chip mapping.
        var converted=new AuthoredPersona(1,List.of(
                new AuthoredPersona.Field("world",List.of(),legacy.path("world").asText(),false),
                new AuthoredPersona.Field("persona",List.of(),"GENTLE · SHY",false),
                new AuthoredPersona.Field("extra",List.of(),legacy.path("note").asText()+"\n말투: "+legacy.path("tone").asText()+"\n장르: "+legacy.path("genre").asText(),false),
                new AuthoredPersona.Field("likes",List.of(),"추가한 취향",false)));
        assertThat(postAs(r.user,r.path()+"/personality",Map.of("authoredPersona",converted,"expectedPersonaHash",response.path("personaSourceHash").asText())).getResponse().getStatus()).isEqualTo(200);
        var saved=petRepository.findById(r.pet).orElseThrow();
        assertThat(LegacyPersonaSource.of(saved)).isEqualTo(LegacyPersonaSource.of(before));
        assertThat(saved.getClockStartedAt()).isEqualTo(clock);assertThat(saved.getFirstIntroStep()).isNull();
        assertThat(saved.getName()).isEqualTo(before.getName());
        assertThat(PromptAssembler.system(new PersonaSheetBuilder(null).build(saved))).contains("GENTLE · SHY","조용한 존댓말","일상 판타지","옛세계","추가한 취향");
        assertThat(frozenHash).isEqualTo(PersonaSourceVersion.of(before)); // captured historical source object unchanged
    }
    @Test void competingLegacyEditInvalidatesPreloadedSourceWithoutWritingAuthoredOrAddress()throws Exception {
        var r=room();String old=hash(r);
        assertThat(postAs(r.user,r.path()+"/personality",Map.of("personality","COOL","world","다른 탭의 새 세계")).getResponse().getStatus()).isEqualTo(200);
        assertThat(hash(r)).isNotEqualTo(old);
        assertThat(postAs(r.user,r.path()+"/personality",Map.of("authoredPersona",source("낡은 편집"),"callMe","새호칭","expectedPersonaHash",old)).getResponse().getStatus()).isEqualTo(409);
        var pet=petRepository.findById(r.pet).orElseThrow();
        assertThat(pet.getAuthoredPersona()).isNull();assertThat(pet.getWorld()).isEqualTo("다른 탭의 새 세계");assertThat(pet.getCallMe()).isEqualTo("민지");
    }
    @Test void originalLegacyPersonalityContractStillWorksWithoutResettingSource()throws Exception{
        var r=room();assertThat(postAs(r.user,r.path()+"/personality",Map.of("personality","LIVELY","world","구름위")).getResponse().getStatus()).isEqualTo(200);
        var p=petRepository.findById(r.pet).orElseThrow();assertThat(p.getPersonality()).isEqualTo(Personality.LIVELY);assertThat(p.getWorld()).isEqualTo("구름위");assertThat(p.getAuthoredPersona()).isNull();
    }
}
