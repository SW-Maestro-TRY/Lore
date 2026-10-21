package com.lore.zzal.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.client.*;
import com.lore.zzal.pet.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalFixturePostProcessorTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final S3Client s3 = mock(S3Client.class);
    private final Map<String, byte[]> objects = new HashMap<>();
    private final String prefix = "images/zzal/pets/7/basic/1";

    @Test void writesExactCallerPrefixAndSameSetAnchorsThenRetriesWithoutOverwriting() throws Exception {
        bundle(); memoryStorage();
        var provider = provider();
        generate(provider);
        assertThat(objects).hasSize(9).containsKey(prefix + "/anchors.json");
        for (var entry : objects.entrySet()) assertThat(entry.getValue()).isEqualTo(Files.readAllBytes(directory.resolve(entry.getKey().substring(prefix.length()+1))));
        generate(provider);
        verify(s3, times(9)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test void refusesDifferentExistingBytesBeforeAnyWrites() throws Exception {
        bundle(); memoryStorage(); objects.put(prefix + "/anchors.json", new byte[]{99});
        assertThatThrownBy(() -> generate(provider())).isInstanceOf(IllegalStateException.class);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(objects.get(prefix + "/anchors.json")).containsExactly((byte)99);
    }

    @Test void partialFailureCanResumeSameBytesWithoutRewritingCompletedObjects() throws Exception {
        bundle(); memoryStorage(); var provider=provider();
        doAnswer(call -> {var req=call.getArgument(0,PutObjectRequest.class);if(req.key().endsWith("/joy.webp"))throw S3Exception.builder().statusCode(503).build();return store(call.getArgument(0),call.getArgument(1));})
                .when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThatThrownBy(() -> generate(provider)).isInstanceOf(S3Exception.class);
        assertThat(objects).hasSize(2);
        memoryStorage();generate(provider);
        assertThat(objects).hasSize(9);
        verify(s3, times(1)).putObject(argThat((PutObjectRequest r)->r.key().equals(prefix+"/base.webp")),any(RequestBody.class));
    }

    @Test void conditionalPutRaceNeverReplacesDifferentBytes() throws Exception {
        bundle();memoryStorage();
        doAnswer(call->{var request=call.getArgument(0,PutObjectRequest.class);objects.put(request.key(),new byte[]{88});throw S3Exception.builder().statusCode(412).build();})
                .when(s3).putObject(any(PutObjectRequest.class),any(RequestBody.class));
        assertThatThrownBy(()->generate(provider())).isInstanceOf(IllegalStateException.class);
        assertThat(objects.get(prefix+"/base.webp")).containsExactly((byte)88);
    }

    @Test void rejectsWrongScopeRoundKeysAndLayerWithoutStorageAccess() throws Exception {
        bundle();var provider=provider();
        for(String path:List.of("images/zzal/pets/7/basic/2","images/zzal/pets/8/basic/1","images/zzal/demo/basic/1",prefix+"/../2"))
            assertThatThrownBy(()->provider.open(path,"v1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->provider.open(prefix,"v2")).isInstanceOf(IllegalArgumentException.class);
        try(var session=provider.open(prefix,"v1")) {
            assertThatThrownBy(()->session.split("ignored",List.of("base"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->session.split("ignored",LocalFixturePostProcessor.KEYS,"",2)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(s3);
    }

    @Test void hashPinnedBundleRejectsTampering() throws Exception {
        bundle();Files.write(directory.resolve("sleep.webp"),new byte[]{0});
        assertThatThrownBy(this::provider).isInstanceOf(IllegalStateException.class).hasMessageContaining("fixture bundle");
        verifyNoInteractions(s3);
    }

    @Test void guardRejectsProductionAndRemoteEndpointsEvenWithProfileEnabled() {
        var env=local();LocalFixturePostProcessor.requireLocal(env);
        for(String runtime:List.of("prod","staging","production","")){
            var bad=local().withProperty("app.lore.env",runtime);
            assertThatThrownBy(()->LocalFixturePostProcessor.requireLocal(bad)).isInstanceOf(IllegalStateException.class);
        }
        for(String property:List.of("app.zzal.generation.real","app.zzal.generation.real-postprocess"))
            assertThatThrownBy(()->LocalFixturePostProcessor.requireLocal(local().withProperty(property,"true"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->LocalFixturePostProcessor.requireLocal(local().withProperty("app.s3.endpoint","https://s3.amazonaws.com"))).isInstanceOf(IllegalStateException.class);
        var tail=local().withProperty("app.s3.endpoint","http://100.100.100.100:9003");
        assertThatThrownBy(()->LocalFixturePostProcessor.requireLocal(tail)).isInstanceOf(IllegalStateException.class);
        tail.withProperty("app.zzal.generation.local-fixture.storage-endpoint","http://100.100.100.100:9003");LocalFixturePostProcessor.requireLocal(tail);
        env.setActiveProfiles("dev");assertThatThrownBy(()->LocalFixturePostProcessor.requireLocal(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test void defaultOffKeepsOriginalFakeProcessorAndEnabledProductionFailsClosed() {
        var env=new MockEnvironment().withProperty("app.zzal.hatch.states.v1",String.join(",",LocalFixturePostProcessor.KEYS));
        var config=new GenerationConfig();
        assertThat(config.postProcessor(false,mock(S3Storage.class),mock(PipelineScripts.class),"no-python", "v1",60,env,s3,mock(ZzalPetRepository.class))).isInstanceOf(FakePostProcessor.class);
        env.withProperty("app.zzal.generation.local-fixture.enabled","true").withProperty("app.lore.env","prod");
        assertThatThrownBy(()->config.postProcessor(false,mock(S3Storage.class),mock(PipelineScripts.class),"no-python","v1",60,env,s3,mock(ZzalPetRepository.class))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(s3);
    }

    @Test void onlyNewDraftOrHatchingPetsWithNoPreviousRoundAreEligible() throws Exception {
        bundle();var env=local().withProperty("app.zzal.generation.local-fixture.directory",directory.toString())
                .withProperty("app.zzal.generation.local-fixture.new-pets-since","2026-10-21T00:00:00Z").withProperty("app.s3.content-bucket","test");
        var repo=mock(ZzalPetRepository.class);var pet=mock(ZzalPet.class);when(repo.findById(7L)).thenReturn(Optional.of(pet));
        when(pet.getCreatedAt()).thenReturn(Instant.parse("2026-10-21T00:01:00Z"));when(pet.getPhase()).thenReturn(PetPhase.DRAFT);
        var provider=LocalFixturePostProcessor.create(env,s3,repo);provider.open(prefix,"v1").close();
        when(pet.getPhase()).thenReturn(PetPhase.HATCHING);provider.open(prefix,"v1").close();
        when(pet.getCreatedAt()).thenReturn(Instant.parse("2026-10-20T23:59:59Z"));assertThatThrownBy(()->provider.open(prefix,"v1")).isInstanceOf(IllegalArgumentException.class);
        when(pet.getCreatedAt()).thenReturn(Instant.parse("2026-10-21T00:01:00Z"));when(pet.getBasicRound()).thenReturn(1);assertThatThrownBy(()->provider.open(prefix,"v1")).isInstanceOf(IllegalArgumentException.class);
        when(pet.getBasicRound()).thenReturn(0);when(pet.getPhase()).thenReturn(PetPhase.ALIVE);assertThatThrownBy(()->provider.open(prefix,"v1")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3);
    }

    private MockEnvironment local(){var e=new MockEnvironment().withProperty("app.lore.env","dev").withProperty("app.s3.endpoint","http://minio:9000");e.setActiveProfiles("local-existing-assets");return e;}
    private LocalFixturePostProcessor provider(){return new LocalFixturePostProcessor(s3,"test",directory,id->id==7);}
    private void generate(LocalFixturePostProcessor p)throws Exception{try(var session=p.open(prefix,"v1")){session.split("ignored-fake-key",LocalFixturePostProcessor.KEYS,"",1);}}
    private void bundle()throws Exception{
        var hashes=new LinkedHashMap<String,String>();Files.write(directory.resolve("sheet.png"),new byte[]{1,2,3});
        var poses=new LinkedHashMap<String,Object>();for(var key:LocalFixturePostProcessor.KEYS){poses.put(key,Map.of());Files.write(directory.resolve(key+".webp"),("fixture-"+key).getBytes());}
        Files.writeString(directory.resolve("anchors.json"),json.writeValueAsString(Map.of("K",285,"Hw",179,"canvas",Map.of("w",313,"h",350),"poses",poses)));
        for(String name:new ArrayList<>(List.of("base.webp","eat.webp","joy.webp","sad.webp","sick.webp","pet.webp","hello.webp","sleep.webp","anchors.json")))hashes.put(name,hash(Files.readAllBytes(directory.resolve(name))));
        Files.writeString(directory.resolve("manifest.json"),json.writeValueAsString(Map.of("schemaVersion",1,"sourceSHA256",hash(Files.readAllBytes(directory.resolve("sheet.png"))),"files",hashes)));
    }
    private String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private void memoryStorage(){
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(call->{var key=call.getArgument(0,GetObjectRequest.class).key();if(!objects.containsKey(key))throw S3Exception.builder().statusCode(404).build();return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),objects.get(key));});
        when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(call->store(call.getArgument(0),call.getArgument(1)));
    }
    private PutObjectResponse store(PutObjectRequest request,RequestBody body)throws Exception{
        assertThat(request.ifNoneMatch()).isEqualTo("*");assertThat(request.bucket()).isEqualTo("test");
        assertThat(request.contentType()).isEqualTo(request.key().endsWith(".json")?"application/json":"image/webp");
        try(var stream=body.contentStreamProvider().newStream()){objects.put(request.key(),stream.readAllBytes());}return PutObjectResponse.builder().build();
    }
}
