package com.lore.zzal.chat.line;
import org.junit.jupiter.api.Test;import java.time.Duration;import java.net.http.HttpClient;import static org.assertj.core.api.Assertions.*;
class OpenAiChatLineClientBoundsTest {

 java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
 java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> sent=new java.util.concurrent.atomic.AtomicReference<>();
 com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
 com.sun.net.httpserver.HttpServer server; java.net.URI endpoint;
 void start(boolean slow)throws Exception{server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{requests.incrementAndGet();sent.set(json.readTree(e.getRequestBody()));try{if(slow)Thread.sleep(1500);byte[] b="{\"choices\":[{\"message\":{\"content\":\"bounded synthetic response\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}".getBytes();e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);}catch(Exception ignored){}finally{e.close();}});server.start();endpoint=java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");}
 @org.junit.jupiter.api.AfterEach void close(){if(server!=null)server.stop(0);}

 @Test void capAndLegacyDefaultAndUsage()throws Exception{start(false);
 var bounded=new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY","minimal",HttpClient.newHttpClient(),endpoint,64);
 var result=bounded.complete("synthetic system","synthetic user","gpt-5-mini",Duration.ofSeconds(2));assertThat(sent.get().path("max_completion_tokens").asInt()).isEqualTo(64);assertThat(result.inputTokens()).isEqualTo(100);assertThat(result.outputTokens()).isEqualTo(20);assertThat(result.costUsd()).isEqualByComparingTo("0.000065");
 var legacy=new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY","minimal",HttpClient.newHttpClient(),endpoint);legacy.complete("system","user","gpt-5-mini",Duration.ofSeconds(2));assertThat(sent.get().has("max_completion_tokens")).isFalse();assertThat(requests.get()).isEqualTo(2);}
 @Test void timeoutDoesNotRetry()throws Exception{start(true);var bounded=new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY","minimal",HttpClient.newHttpClient(),endpoint,64);assertThatThrownBy(()->bounded.complete("system","user","gpt-5-mini",Duration.ofMillis(500))).isInstanceOf(java.util.concurrent.TimeoutException.class);Thread.sleep(1600);assertThat(requests.get()).isEqualTo(1);}
 @Test void invalidCapRejectedBeforeRequest(){assertThatThrownBy(()->new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY","minimal",0)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY","minimal",-1)).isInstanceOf(IllegalArgumentException.class);}
}
