package com.lore.zzal.generation.client;

/** Explicit scopes must be opened inside executor tasks, and always closed on that same thread. */
public final class ProviderUsageContext implements AutoCloseable {
    public record Links(String feature, Long petId, Long jobId, String requestId, Integer attempt) {}
    private static final ThreadLocal<Links> CURRENT = new ThreadLocal<>();
    private final Links previous;
    private ProviderUsageContext(Links links) { previous=CURRENT.get(); CURRENT.set(links); }
    public static ProviderUsageContext open(String feature, Long petId, Long jobId, String requestId, Integer attempt) {
        return new ProviderUsageContext(new Links(feature,petId,jobId,requestId,attempt));
    }
    public static Links current() { return CURRENT.get(); }
    @Override public void close() { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
}
