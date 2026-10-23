package com.lore.zzal.generation.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

/** Local sample output only. Existing hatch steps still own rounds, completion and motion rows. */
public final class LocalFixturePostProcessor implements PostProcessor {
    public static final List<String> KEYS = List.of("base", "eat", "joy", "sad", "sick", "pet", "hello", "sleep");
    private static final Pattern PREFIX = Pattern.compile("images/zzal/pets/([1-9][0-9]*)/basic/1");
    private final S3Client storage;
    private final String bucket;
    private final LongPredicate eligible;
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final byte[] sheet;

    public static LocalFixturePostProcessor create(Environment env, S3Client storage, ZzalPetRepository pets) {
        requireLocal(env);
        Instant since = Instant.parse(env.getRequiredProperty("app.zzal.generation.local-fixture.new-pets-since"));
        return new LocalFixturePostProcessor(storage, env.getRequiredProperty("app.s3.content-bucket"),
                Path.of(env.getRequiredProperty("app.zzal.generation.local-fixture.directory")), id ->
                pets.findById(id).map(p -> p.getCreatedAt() != null && !p.getCreatedAt().isBefore(since)
                        && p.getBasicRound() == 0 && (p.getPhase() == PetPhase.DRAFT || p.getPhase() == PetPhase.HATCHING)).orElse(false));
    }

    public static void requireLocal(Environment env) {
        String runtime = env.getProperty("app.lore.env", "").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("local", "dev").contains(runtime)
                || !env.acceptsProfiles(Profiles.of("local-existing-assets"))
                || env.getProperty("app.zzal.generation.real", Boolean.class, false)
                || env.getProperty("app.zzal.generation.real-postprocess", Boolean.class, false)) {
            throw new IllegalStateException("Local fixture requires local-existing-assets profile, local/dev runtime and fake generation/postprocess");
        }
        String endpoint = env.getRequiredProperty("app.s3.endpoint");
        URI address = URI.create(endpoint);
        String host = address.getHost();
        // Preserve the existing signed upload origin on a local Tailscale stack; require an exact explicit pin.
        String[] octets = host == null ? new String[0] : host.split("\\.");
        boolean tailscale = octets.length == 4 && octets[0].equals("100") && octets[1].matches("[0-9]{1,3}")
                && Integer.parseInt(octets[1]) >= 64 && Integer.parseInt(octets[1]) <= 127
                && address.getScheme().equals("http")
                && endpoint.equals(env.getProperty("app.zzal.generation.local-fixture.storage-endpoint", ""));
        if (host == null || !(Set.of("minio", "localhost", "127.0.0.1", "[::1]", "::1").contains(host) || tailscale)) {
            throw new IllegalStateException("Local fixture requires a local object-storage endpoint");
        }
    }

    /** Loads a hash-pinned same-sheet bundle once, so a running hatch cannot mix source revisions. */
    public LocalFixturePostProcessor(S3Client storage, String bucket, Path directory, LongPredicate eligible) {
        this.storage = storage; this.bucket = bucket; this.eligible = eligible;
        try {
            var manifest = new ObjectMapper().readTree(Files.readString(directory.resolve("manifest.json")));
            if (manifest.path("schemaVersion").asInt() != 1) throw new IllegalArgumentException("fixture schema");
            sheet = Files.readAllBytes(directory.resolve("sheet.png"));
            checkHash(sheet, manifest.path("sourceSHA256").asText());
            var names = new ArrayList<>(KEYS.stream().map(k -> k + ".webp").toList());
            names.add("anchors.json");
            for (String name : names) {
                byte[] bytes = Files.readAllBytes(directory.resolve(name));
                checkHash(bytes, manifest.path("files").path(name).asText());
                files.put(name, bytes);
            }
            var anchors = new ObjectMapper().readTree(files.get("anchors.json"));
            if (!anchors.isObject() || anchors.path("K").asDouble() <= 0 || anchors.path("Hw").asDouble() <= 0
                    || anchors.path("canvas").path("w").asInt() != 313 || anchors.path("canvas").path("h").asInt() != 350
                    || !KEYS.stream().allMatch(k -> anchors.path("poses").path(k).isObject())) {
                throw new IllegalArgumentException("fixture anchors");
            }
        } catch (Exception e) { throw new IllegalStateException("Invalid local basic8 fixture bundle", e); }
    }

    /** Only new local hatch sheets use the already pinned same-set source; other fake calls stay fake. */
    public ImageClient imageClient(ImageClient fallback) {
        return (prompt, references, outputKey, spec) -> {
            if (outputKey == null || !outputKey.endsWith("/sheet.png"))
                return fallback.generate(prompt, references, outputKey, spec);
            var match = Pattern.compile("images/zzal/pets/([1-9][0-9]*)/sheet\\.png").matcher(outputKey);
            if (!match.matches() || !eligible.test(Long.parseLong(match.group(1))))
                throw new IllegalArgumentException("Local fixture sheet only accepts newly created hatch pets");
            putAbsentOrSame(outputKey, sheet);
            return new ImageClient.Result(outputKey, java.math.BigDecimal.ZERO);
        };
    }

    @Override public Session open(String prefix, String version) {
        var match = PREFIX.matcher(prefix);
        if (!match.matches() || !"v1".equals(version) || !eligible.test(Long.parseLong(match.group(1)))) {
            throw new IllegalArgumentException("Local fixture only accepts newly created first-round hatch pets");
        }
        return new Session() {
            private boolean split;
            @Override public void split(String grid, List<String> keys) { split(grid, keys, "", 1); }
            @Override public void split(String grid, List<String> keys, String postures) { split(grid, keys, postures, 1); }
            @Override public void split(String grid, List<String> keys, String postures, int layer) {
                if (split || layer != 1 || !KEYS.equals(keys)) throw new IllegalArgumentException("Local fixture supports basic8 only");
                // Deliberately substitute the completed output, never feed fake image keys to a real postprocessor.
                split = true;
            }
            @Override public void seedAnchors(String key) { throw new UnsupportedOperationException("Local fixture has no layer2"); }
            @Override public void carryOver(String from, List<String> keys) { throw new UnsupportedOperationException("Local fixture has no layer2"); }
            @Override public void close() {
                if (!split) return;
                // Preflight every destination before writing; retries accept only the exact same bytes.
                for (var file : files.entrySet()) assertAbsentOrSame(prefix + "/" + file.getKey(), file.getValue());
                for (var file : files.entrySet()) putAbsentOrSame(prefix + "/" + file.getKey(), file.getValue());
            }
        };
    }

    private boolean assertAbsentOrSame(String key, byte[] expected) {
        try {
            byte[] existing = storage.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
            if (!Arrays.equals(existing, expected)) throw new IllegalStateException("Local fixture refuses to replace existing asset");
            return true;
        } catch (S3Exception e) { if (e.statusCode() == 404) return false; throw e; }
    }

    private void putAbsentOrSame(String key, byte[] bytes) {
        if (assertAbsentOrSame(key, bytes)) return;
        try {
            storage.putObject(PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*")
                    .contentType(key.endsWith(".json") ? "application/json" : key.endsWith(".png") ? "image/png" : "image/webp")
                    .cacheControl("public, max-age=31536000, immutable").build(), RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            if (e.statusCode() != 412 || !assertAbsentOrSame(key, bytes)) throw e;
        }
    }

    private static void checkHash(byte[] bytes, String expected) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equals(expected)) throw new IllegalArgumentException("fixture SHA256 mismatch");
    }
}
