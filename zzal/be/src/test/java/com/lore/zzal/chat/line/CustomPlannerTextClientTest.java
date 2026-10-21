package com.lore.zzal.chat.line;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.zzal.attendance.*;
import com.lore.zzal.generation.client.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomPlannerTextClientTest {
    @Test void productionPlannerPassesExplicitNoneCapInOneSyntheticRequest() throws Exception {
        HttpClient http=mock(HttpClient.class); HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"{\\\"motions\\\":[]}\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}");
        var sent=new java.util.concurrent.atomic.AtomicReference<HttpRequest>();
        when(http.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{sent.set(inv.getArgument(0));return CompletableFuture.completedFuture(response);});
        var adapter=new CustomPlannerTextClient(new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY","none",http,URI.create("https://never-executed.invalid/"),6144),Duration.ofSeconds(90));
        var result=adapter.generate("SYNTHETIC_PLAN_DATA",List.of(),ModelSpec.of("gpt-5.6-luna"));
        var bytes=new ByteArrayOutputStream();
        sent.get().bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);} public void onNext(ByteBuffer b){byte[] a=new byte[b.remaining()];b.get(a);bytes.writeBytes(a);} public void onError(Throwable t){throw new AssertionError(t);} public void onComplete(){}
        });
        var body=new ObjectMapper().readTree(bytes.toByteArray());
        assertThat(body.path("model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("none");
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(6144);
        assertThat(sent.get().timeout()).contains(Duration.ofSeconds(90));
        assertThat(result.text()).isEqualTo("{\"motions\":[]}");
        assertThat(result.costUsd()).isEqualByComparingTo(new BigDecimal("0.000044"));
        verify(http,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)); verifyNoMoreInteractions(http);
    }
    @Test void providerTimeoutIsNotRetriedAndReferencesAreRejectedBeforeCall() throws Exception {
        var client=mock(ChatLineClient.class); var adapter=new CustomPlannerTextClient(client,Duration.ofSeconds(90));
        assertThatThrownBy(()->adapter.generate("data",List.of("images/ref.png"),ModelSpec.of("gpt-5.6-luna"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
        when(client.complete(anyString(),anyString(),anyString(),any())).thenThrow(new TimeoutException("synthetic"));
        assertThatThrownBy(()->adapter.generate("data",List.of(),ModelSpec.of("gpt-5.6-luna"))).isInstanceOf(TimeoutException.class);
        verify(client,times(1)).complete(anyString(),eq("data"),eq("gpt-5.6-luna"),eq(Duration.ofSeconds(90))); verifyNoMoreInteractions(client);
    }
    @Test void productionWiringUsesDedicatedPlannerWithoutTouchingSharedIdentityClient() {
        var env=valid(); var shared=mock(TextClient.class);var publication=mock(CustomBundlePublicationService.class);
        var service=new CustomProviderConfiguration().realCustomBundleService(env,mock(CustomPlanningService.class),mock(BundleJobWorker.class),shared,mock(ImageClient.class),mock(PostProcessor.class),mock(com.lore.common.s3.S3Storage.class),publication);
        var planner=ReflectionTestUtils.getField(service,"text");assertThat(planner).isInstanceOf(CustomPlannerTextClient.class);
        var client=ReflectionTestUtils.getField(planner,"client");
        assertThat(ReflectionTestUtils.getField(client,"reasoningEffort")).isEqualTo("none");
        assertThat(ReflectionTestUtils.getField(client,"maxCompletionTokens")).isEqualTo(6144);
        assertThat(ReflectionTestUtils.getField(planner,"timeout")).isEqualTo(Duration.ofSeconds(90));
        assertThat(ReflectionTestUtils.getField(service,"plannerModel")).isEqualTo("gpt-5.6-luna");verifyNoInteractions(shared);
    }
    @Test void incompleteOrInvalidPlannerSettingsFailBeforeProviderInvocation() {
        var env=valid();env.setProperty("app.zzal.openai.api-key","");
        assertThatThrownBy(()->new CustomProviderConfiguration().realCustomBundleService(env,null,null,null,mock(ImageClient.class),null,null,mock(CustomBundlePublicationService.class))).hasMessageContaining("app.zzal.openai.api-key");
        env.setProperty("app.zzal.openai.api-key","SYNTHETIC_NOT_A_KEY");env.setProperty("app.zzal.custom.planner-max-completion-tokens","0");
        assertThatThrownBy(()->new CustomProviderConfiguration().realCustomBundleService(env,null,null,null,mock(ImageClient.class),null,null,mock(CustomBundlePublicationService.class))).hasMessageContaining("positive");
    }
    @Test void identityCapBindingIsOptionalAndTransmittedWithoutChangingTheModel() throws Exception {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(IdentityBinding.class);
        runner.withPropertyValues("app.zzal.openai.identity-max-completion-tokens=${ZZAL_OPENAI_IDENTITY_MAX_COMPLETION_TOKENS:}").run(ctx->{
            var owner=ctx.getBean(com.lore.zzal.generation.GenerationConfig.class);
            var client=owner.textClient(true,0,"SYNTHETIC_NOT_A_KEY",mock(com.lore.common.s3.S3Storage.class));
            assertThat(ReflectionTestUtils.getField(client,"maxCompletionTokens")).isNull();
        });
        runner.withPropertyValues("app.zzal.openai.identity-max-completion-tokens=4096").run(ctx->{
            var owner=ctx.getBean(com.lore.zzal.generation.GenerationConfig.class);
            var client=owner.textClient(true,0,"SYNTHETIC_NOT_A_KEY",mock(com.lore.common.s3.S3Storage.class));
            assertThat(ReflectionTestUtils.getField(client,"maxCompletionTokens")).isEqualTo(4096);
            HttpClient http=mock(HttpClient.class);HttpResponse<String> response=mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);when(response.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));
            when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"synthetic identity\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}");
            var request=new java.util.concurrent.atomic.AtomicReference<HttpRequest>();
            when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{request.set(inv.getArgument(0));return response;});
            ReflectionTestUtils.setField(client,"http",http);
            client.generate("synthetic identity prompt",List.of(),ModelSpec.of("gpt-5"));
            var data=new ByteArrayOutputStream();request.get().bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
                public void onSubscribe(Flow.Subscription sub){sub.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){byte[] a=new byte[b.remaining()];b.get(a);data.writeBytes(a);}public void onError(Throwable t){throw new AssertionError(t);}public void onComplete(){}
            });
            var payload=new ObjectMapper().readTree(data.toByteArray());
            assertThat(payload.path("model").asText()).isEqualTo("gpt-5");assertThat(payload.path("max_completion_tokens").asInt()).isEqualTo(4096);
            verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        });
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class IdentityBinding {
        @org.springframework.context.annotation.Bean com.lore.zzal.generation.GenerationConfig owner(){return new com.lore.zzal.generation.GenerationConfig();}
    }
    private MockEnvironment valid(){return new MockEnvironment().withProperty("app.zzal.generation.real","true").withProperty("app.zzal.generation.real-postprocess","true").withProperty("app.zzal.attendance.cli-enabled","true").withProperty("app.zzal.openai.api-key","SYNTHETIC_NOT_A_KEY").withProperty("app.zzal.custom.planner-model","gpt-5.6-luna").withProperty("app.zzal.custom.provider","api").withProperty("app.zzal.custom.image-model","gpt-image-2").withProperty("app.zzal.custom.image-size","1248x1248").withProperty("app.zzal.custom.postprocess-version","v1");}
}
