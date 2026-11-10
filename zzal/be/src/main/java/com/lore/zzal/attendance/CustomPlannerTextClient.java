package com.lore.zzal.attendance;

import com.lore.zzal.chat.line.ChatLineClient;
import com.lore.zzal.generation.client.ModelSpec;
import com.lore.zzal.generation.client.TextClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Dedicated text-only custom planner; one provider request, independent from room/identity settings. */
public final class CustomPlannerTextClient implements TextClient {
    private final ChatLineClient client;
    private final Duration timeout;

    public CustomPlannerTextClient(ChatLineClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive planner timeout required");
    }

    @Override
    public Result generate(String prompt, List<String> references, ModelSpec model) throws Exception {
        if (!references.isEmpty()) throw new IllegalArgumentException("Custom planner is text-only");
        var result = client.complete("Return one valid JSON object matching the supplied custom-motion schema.",
                prompt, model.model(), timeout);
        return new Result(result.text(), result.costUsd());
    }
}
