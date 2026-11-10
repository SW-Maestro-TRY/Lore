package com.lore.zzal.chat.line;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OpenAiChatLineClientTest {
    @Test void actualHttpAdapterCarriesWholePromptModelAndSingleRequest() throws Exception {
        var captured=new AtomicReference<String>();var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{
            count.incrementAndGet();captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"choices\":[{\"message\":{\"content\":\"{\\\"line\\\":\\\"안녕\\\"}\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var client=new OpenAiChatLineClient("stub-not-real","minimal",HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"));
            var result=client.complete("authored6·호칭=별님·language=Japanese·USER기억은USER", "원문 그대로", "gpt-5-mini",Duration.ofSeconds(2));
            var req=new ObjectMapper().readTree(captured.get());
            assertThat(count.get()).isEqualTo(1);assertThat(req.path("model").asText()).isEqualTo("gpt-5-mini");
            assertThat(req.path("reasoning_effort").asText()).isEqualTo("minimal");assertThat(req.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(req.path("messages").get(0).path("content").asText()).isEqualTo("authored6·호칭=별님·language=Japanese·USER기억은USER");
            assertThat(req.path("messages").get(1).path("content").asText()).isEqualTo("원문 그대로");
            assertThat(result.inputTokens()).isEqualTo(100);assertThat(result.outputTokens()).isEqualTo(20);assertThat(result.text()).isEqualTo("{\"line\":\"안녕\"}");
        } finally {server.stop(0);}
    }
    @Test void httpFailureDoesNotRetryInsideTransport() throws Exception {
        var count=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",x->{count.incrementAndGet();x.sendResponseHeaders(503,-1);x.close();});server.start();
        try {
            var client=new OpenAiChatLineClient("stub-not-real",null,HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"));
            assertThatThrownBy(()->client.complete("system","user","gpt-5-mini",Duration.ofSeconds(2))).hasMessageContaining("HTTP 503");
            assertThat(count.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
}
