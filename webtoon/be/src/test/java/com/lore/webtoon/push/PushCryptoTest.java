package com.lore.webtoon.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 푸시 암호화를 직접 짰으므로 <b>RFC 8291 부록 A 의 예제 값으로 확인한다.</b>
 * 같은 키·salt·본문을 넣었을 때 RFC 에 적힌 결과와 한 바이트도 다르면 안 된다 —
 * 다르면 브라우저가 풀지 못해 알림이 조용히 사라진다.
 */
class PushCryptoTest {

    private static byte[] b(String s) {
        return PushCrypto.unb64(s.replace(" ", ""));
    }

    @Test
    @DisplayName("RFC 8291 예제와 같은 암호문이 나온다")
    void rfc8291Example() throws Exception {
        byte[] plaintext = b("V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24");
        byte[] asPublic = b("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        byte[] asPrivate = b("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw");
        byte[] uaPublic = b("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] salt = b("DGv6ra1nlYgDCS1FRnbzlw");
        byte[] auth = b("BTBZMqHH6r4Tts7J_aSIgg");

        KeyPair as = new KeyPair(PushCrypto.publicKey(asPublic), PushCrypto.privateKey(asPrivate));
        byte[] out = PushCrypto.encrypt(uaPublic, auth, plaintext, as, salt);

        // RFC 는 머리(86바이트)와 암호문을 따로 base64 로 적었다 — 바이트로 이어 붙여 비교한다.
        byte[] header = b("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
                + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        byte[] sealed = b("8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ");
        byte[] expected = new byte[header.length + sealed.length];
        System.arraycopy(header, 0, expected, 0, header.length);
        System.arraycopy(sealed, 0, expected, header.length, sealed.length);
        assertThat(header).hasSize(86);
        assertThat(out).isEqualTo(expected);
    }

    @Test
    @DisplayName("공개키는 65바이트 모양 그대로 오간다")
    void publicKeyRoundTrip() throws Exception {
        KeyPair pair = PushCrypto.generate();
        byte[] raw = PushCrypto.uncompressed((ECPublicKey) pair.getPublic());
        assertThat(raw).hasSize(65);
        assertThat(PushCrypto.uncompressed(PushCrypto.publicKey(raw))).isEqualTo(raw);
    }

    @Test
    @DisplayName("VAPID 토큰은 공개키로 검증되는 ES256 서명을 단다")
    void vapidSignatureVerifies() throws Exception {
        KeyPair pair = PushCrypto.generate();
        String jwt = PushCrypto.vapidJwt("https://fcm.googleapis.com", "mailto:a@b.c",
                (java.security.interfaces.ECPrivateKey) pair.getPrivate(), 1_900_000_000L);
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"https://fcm.googleapis.com\"").contains("\"exp\":1900000000");

        Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
        verify.initVerify(pair.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }
}
