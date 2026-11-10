package com.lore.zzal.generation.client;
public final class UsageStub implements AutoCloseable {
 public final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
 public final com.sun.net.httpserver.HttpServer server;public volatile long delay;public volatile boolean requestId=true;
 public volatile String body="{\"id\":\"response-test-1\",\"model\":\"gpt-5-mini\",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"completion_tokens_details\":{\"reasoning_tokens\":5}},\"choices\":[{\"message\":{\"content\":\"SECRET_RESPONSE_TEXT\"}}],\"data\":[{\"b64_json\":\"AA==\"}]}";
 public UsageStub()throws Exception{server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();try{if(delay>0)Thread.sleep(delay);if(requestId)e.getResponseHeaders().add("x-request-id","req-test-1");e.getResponseHeaders().add("X-Secret-Header","SECRET_HEADER");byte[] b=body.getBytes();e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);}catch(Exception ignored){}finally{e.close();}});server.start();}
 public java.net.URI endpoint(){return java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");}
 public void close(){server.stop(0);}
}
