package com.lore.zzal.generation.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.analytics.AnalyticsEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;

/** Optional persistent volume spool. Empty configuration explicitly means memory fallback only. */
@Component
public final class ProviderUsageSpool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    private final java.util.concurrent.atomic.AtomicLong failedReads=new java.util.concurrent.atomic.AtomicLong();
    public ProviderUsageSpool(@Value("${app.zzal.metrics.spool-directory:}") String configured) throws java.io.IOException {
        directory=configured==null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath();
        if(directory!=null) {
            if(!Path.of(configured).isAbsolute()) throw new IllegalArgumentException("metrics spool requires an absolute persistent-volume path");
            Files.createDirectories(directory);
            if(!Files.isWritable(directory)) throw new IllegalArgumentException("metrics spool must be writable");
        } else org.slf4j.LoggerFactory.getLogger(getClass()).warn("Metrics durable spool is not configured; uncommitted retry rows are memory-only");
    }
    record Entry(String id,String name,Long userId,String props,String occurredAt,String receivedAt) {
        AnalyticsEvent row() { return AnalyticsEvent.of(name,"00000000000000000000000000000000",userId,props,null,null,"server",null,null,
                Instant.parse(occurredAt),Instant.parse(receivedAt)).envelope(id,2,null,null,null).identityStatus("server").serverOrigin(); }
    }
    private Path path(String id) {
        if(id==null || !id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("spool event UUID required");
        return directory.resolve(id+".json");
    }
    public synchronized void put(AnalyticsEvent row) throws java.io.IOException {
        if(directory==null) return;
        Path target=path(row.getEventId());
        if(Files.exists(target)) return;
        byte[] bytes=JSON.writeValueAsBytes(new Entry(row.getEventId(),row.getName(),row.getUserId(),row.getProps(),row.getOccurredAt().toString(),row.getReceivedAt().toString()));
        Path temp=Files.createTempFile(directory,"pending-",".tmp");
        try {
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
            syncDirectory();
        } finally { Files.deleteIfExists(temp); }
    }
    public synchronized void remove(String id) throws java.io.IOException {
        if(directory!=null) { Files.deleteIfExists(path(id)); syncDirectory(); }
    }
    private void syncDirectory() throws java.io.IOException {
        try(var channel=FileChannel.open(directory,StandardOpenOption.READ)) { channel.force(true); }
    }
    public List<AnalyticsEvent> pending(int limit) throws java.io.IOException {
        if(directory==null) return List.of();
        try(var paths=Files.list(directory)) {
            var out=new java.util.ArrayList<AnalyticsEvent>();
            for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".json")).sorted().limit(limit).toList()) {
                // Corrupt data is retained and reported, never silently deleted.
                try { out.add(JSON.readValue(Files.readAllBytes(path),Entry.class).row()); }
                catch(Exception failure) {
                    failedReads.incrementAndGet();
                    org.slf4j.LoggerFactory.getLogger(getClass()).error("Corrupt metrics spool file quarantined without deletion: {}",path.getFileName(),failure);
                    Path quarantine=directory.resolve("quarantine");Files.createDirectories(quarantine);
                    Path target=quarantine.resolve(path.getFileName());
                    if(Files.exists(target))target=quarantine.resolve(java.util.UUID.randomUUID()+"-"+path.getFileName());
                    Files.move(path,target,StandardCopyOption.ATOMIC_MOVE);
                    try(var channel=FileChannel.open(quarantine,StandardOpenOption.READ)) { channel.force(true); }
                    syncDirectory();
                }
            }
            return List.copyOf(out);
        }
    }
    public long failedReads() { return failedReads.get(); }
    public boolean enabled() { return directory!=null; }
}
