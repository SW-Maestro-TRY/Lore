package com.lore.zzal.diary;

import com.lore.zzal.chat.line.ChatLineClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.UUID;

/** Reuses the existing one-request HTTP adapter, deliberately bypassing chat LineChain retries/fallbacks. */
public final class ChatLineDiaryModel implements DiaryModel {
    private final ChatLineClient client;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper json=new ObjectMapper();
    public ChatLineDiaryModel(ChatLineClient client,String model,Duration timeout){
        if(timeout==null||timeout.isZero()||timeout.isNegative())throw new IllegalArgumentException("Positive diary timeout required");
        this.client=client;this.model=model;this.timeout=timeout;
    }
    @Override public Result generate(String snapshot,String language,String prompt){
        String selected=switch(language){case "ko"->"Korean";case "ja"->"Japanese";case "en"->"English";default->throw new IllegalArgumentException("Unsupported diary language");};
        var receipt=json.createObjectNode();receipt.put("executionId",UUID.randomUUID().toString());receipt.put("adapter","ChatLineClient");
        receipt.put("model",model);receipt.put("promptVersion","diary-v1");receipt.put("sourceType","DIARY_FICTION");
        receipt.put("sourceHash",DiaryEntry.hash(snapshot));receipt.put("language",language);receipt.put("startedAt",Instant.now().toString());
        String user;
        try {
            var input=json.createObjectNode();input.put("language",selected);input.put("sourceType","DIARY_FICTION");
            input.set("frozenSourceData",json.readTree(snapshot));user=json.writeValueAsString(input);
        }catch(Exception malformed){throw new IllegalArgumentException("Invalid frozen diary source",malformed);}
        ChatLineClient.Completion completion;
        try {
            // Exactly one request. A timeout has an unknown billing outcome and can never trigger another model request.
            completion=client.complete(prompt+"\nTransport format: return exactly one JSON object with a single string field body. Write that body entirely in "+selected+". No markdown fences or other fields.",user,model,timeout);
        }catch(Exception failure){
            receipt.put("outcome","UNCERTAIN");receipt.put("errorClass",failure.getClass().getSimpleName());
            if(failure instanceof ChatLineClient.BilledException billed)receipt.put("costUsd",billed.costUsd());
            receipt.put("completedAt",Instant.now().toString());throw new ExecutionUncertain(receipt.toString(),failure);
        }
        receipt.put("completedAt",Instant.now().toString());
        if(completion==null){receipt.put("outcome","INVALID_EMPTY");return new Result(null,receipt.toString());}
        receipt.put("costUsd",completion.costUsd());receipt.put("inputTokens",completion.inputTokens());receipt.put("outputTokens",completion.outputTokens());
        receipt.put("rawResponseHash",DiaryEntry.hash(java.util.Objects.toString(completion.text(),"")));
        try {
            var output=json.readTree(completion.text());
            if(output==null||!output.isObject()||output.size()!=1||!output.path("body").isTextual()||output.path("body").asText().isBlank()){
                receipt.put("outcome","INVALID_OUTPUT");return new Result(null,receipt.toString());
            }
            String body=output.path("body").asText();receipt.put("outcome","COMPLETED");receipt.put("bodyHash",DiaryEntry.hash(body));
            return new Result(body,receipt.toString());
        }catch(Exception malformed){receipt.put("outcome","INVALID_JSON");return new Result(null,receipt.toString());}
    }
}
