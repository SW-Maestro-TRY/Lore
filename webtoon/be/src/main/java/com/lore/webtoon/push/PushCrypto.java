package com.lore.webtoon.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 웹푸시 본문 암호화(RFC 8291, aes128gcm)와 VAPID 서명(RFC 8292).
 *
 * <h2>라이브러리를 안 쓰는 이유</h2>
 *
 * 자바 웹푸시 라이브러리는 BouncyCastle 을 끌고 오는데, 하는 일은 P-256 ECDH ·
 * HKDF · AES-GCM · ES256 서명 넷뿐이고 넷 다 JDK 에 있다. 의존성을 늘리지 않고
 * 여기서 직접 한다. 맞게 했는지는 RFC 8291 부록 A 의 예제 값을 그대로 넣어
 * 같은 결과가 나오는지로 확인한다({@code PushCryptoTest}).
 */
final class PushCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /** 한 레코드 크기. 알림 한 통은 이보다 훨씬 작아서 레코드는 늘 하나다. */
    private static final int RECORD_SIZE = 4096;

    static final ECParameterSpec P256 = p256();

    private PushCrypto() {
    }

    /** 브라우저가 준 키({@code p256dh} · {@code auth})로 본문을 잠근다. 매번 새 키쌍·salt 를 쓴다. */
    static byte[] encrypt(byte[] uaPublic, byte[] authSecret, byte[] plaintext) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(uaPublic, authSecret, plaintext, generate(), salt);
    }

    /** 키쌍과 salt 를 밖에서 받는 판 — 예제 값으로 검증하려고 둔다. */
    static byte[] encrypt(byte[] uaPublic, byte[] authSecret, byte[] plaintext,
                          KeyPair as, byte[] salt) throws GeneralSecurityException {
        byte[] asPublic = uncompressed((ECPublicKey) as.getPublic());

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(publicKey(uaPublic), true);
        byte[] ecdhSecret = ka.generateSecret();

        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0},
                uaPublic, asPublic);
        byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[]{1}));

        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, info("Content-Encoding: aes128gcm")), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, info("Content-Encoding: nonce")), 12);

        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] sealed = gcm.doFinal(concat(plaintext, new byte[]{2}));   // 0x02 = 마지막 레코드 표시

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), sealed);
    }

    /**
     * 푸시 서버에 "보내는 사람이 우리다"를 밝히는 토큰(VAPID).
     *
     * @param audience 푸시 서버의 출처(scheme://host) — endpoint 마다 다르다
     * @param subject  연락처({@code mailto:...}). 푸시 서버가 문제가 생기면 여기로 연락한다
     */
    static String vapidJwt(String audience, String subject, ECPrivateKey key, long expiresAtEpochSec)
            throws GeneralSecurityException {
        String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"aud\":\"" + audience + "\",\"exp\":" + expiresAtEpochSec
                + ",\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
        String signing = header + "." + claims;
        Signature es256 = Signature.getInstance("SHA256withECDSAinP1363Format");
        es256.initSign(key);
        es256.update(signing.getBytes(StandardCharsets.US_ASCII));
        return signing + "." + b64(es256.sign());
    }

    static KeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return gen.generateKeyPair();
    }

    /** 0x04 || X || Y (65바이트) → 공개키. 브라우저의 {@code p256dh} 와 VAPID 공개키가 이 모양이다. */
    static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) {
            throw new GeneralSecurityException("P-256 공개키 모양이 아닙니다");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    /** 32바이트 d → 개인키. VAPID 개인키가 이 모양이다. */
    static ECPrivateKey privateKey(byte[] d) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
    }

    static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        put32(key.getW().getAffineX(), out, 1);
        put32(key.getW().getAffineY(), out, 33);
        return out;
    }

    static String b64(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    /** 브라우저가 주는 값은 base64url 이지만 끝의 = 가 붙어 오기도 해서 떼고 읽는다. */
    static byte[] unb64(String s) {
        return B64D.decode(s.trim().replace("=", "").replace('+', '-').replace('/', '_'));
    }

    private static void put32(BigInteger n, byte[] out, int at) {
        byte[] raw = n.toByteArray();                 // 부호 바이트가 붙거나 앞 0 이 빠질 수 있다
        int len = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - len, out, at + 32 - len, len);
    }

    private static byte[] info(String label) {
        return concat(label.getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1});
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("이 JDK 에 P-256 이 없습니다", e);
        }
    }
}
