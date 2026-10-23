package com.lore.zzal.pet.persona;
import com.lore.zzal.pet.ZzalPet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
/** Opaque compare token for source+address; schema version remains 1 and is not an edit revision. */
public final class PersonaSourceVersion {
    private PersonaSourceVersion() { }
    /** Edit CAS only: keep of() stable for immutable bundle snapshots and authored clients. */
    public static String forEditing(ZzalPet pet) {
        if (pet.getAuthoredPersona() != null) return of(pet);
        LegacyPersonaSource legacy = LegacyPersonaSource.of(pet);
        StringBuilder raw = new StringBuilder("legacy-edit-v1\n").append(of(pet));
        append(raw, legacy.world());
        append(raw, legacy.note());
        append(raw, legacy.personalities().toString());
        append(raw, legacy.tone());
        append(raw, legacy.genre());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void append(StringBuilder out, String value) {
        // Null and empty are distinct; length framing prevents separator-content collisions.
        if (value == null) out.append("|-1:");
        else out.append('|').append(value.length()).append(':').append(value);
    }
    public static String of(ZzalPet pet) {
        String raw=Objects.toString(new AuthoredPersonaConverter().convertToDatabaseColumn(pet.getAuthoredPersona()),"null")
                +"\n"+Objects.toString(pet.getCallMe(),"null")+"\n"+Objects.toString(pet.getCallMeSource(),"null");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
