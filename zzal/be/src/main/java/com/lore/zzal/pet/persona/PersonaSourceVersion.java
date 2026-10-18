package com.lore.zzal.pet.persona;
import com.lore.zzal.pet.ZzalPet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
/** Opaque compare token for source+address; schema version remains 1 and is not an edit revision. */
public final class PersonaSourceVersion {
    private PersonaSourceVersion() { }
    public static String of(ZzalPet pet) {
        String raw=Objects.toString(new AuthoredPersonaConverter().convertToDatabaseColumn(pet.getAuthoredPersona()),"null")
                +"\n"+Objects.toString(pet.getCallMe(),"null")+"\n"+Objects.toString(pet.getCallMeSource(),"null");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
