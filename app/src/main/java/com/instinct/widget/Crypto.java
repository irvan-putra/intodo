package com.instinct.widget;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** ECIES (P-256 + ECDH + HKDF-SHA256 + AES-256-GCM) for the widget feed. JVM-testable: no android imports. */
public class Crypto {
    static final String HKDF_INFO = "instinct-widget-feed-v1";

    public static KeyPair generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return g.generateKeyPair();
    }

    /** Base64url of the X.509 SubjectPublicKeyInfo encoding. Not secret. */
    public static String encodePublic(PublicKey key) { return b64e(key.getEncoded()); }
    public static String encodePrivate(PrivateKey key) { return b64e(key.getEncoded()); }
    public static PrivateKey decodePrivate(String s) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(b64d(s)));
    }

    /** payload = base64url( ephemeral uncompressed point (65) || nonce (12) || ciphertext+tag ). */
    public static String decrypt(String payloadB64, PrivateKey priv) throws Exception {
        byte[] blob = b64d(payloadB64);
        if (blob.length < 65 + 12 + 16) throw new IllegalArgumentException("payload too short");
        byte[] ephRaw = Arrays.copyOfRange(blob, 0, 65);
        byte[] nonce = Arrays.copyOfRange(blob, 65, 77);
        byte[] ct = Arrays.copyOfRange(blob, 77, blob.length);
        if (ephRaw[0] != 4) throw new IllegalArgumentException("bad point");
        AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = ap.getParameterSpec(ECParameterSpec.class);
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(ephRaw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(ephRaw, 33, 65));
        PublicKey eph = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(priv);
        ka.doPhase(eph, true);
        byte[] key = hkdf(ka.generateSecret(), HKDF_INFO.getBytes(StandardCharsets.UTF_8), 32);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }

    /** HKDF-SHA256 extract(salt=32 zero bytes)+expand, single block (len <= 32). */
    static byte[] hkdf(byte[] ikm, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] in = new byte[info.length + 1];
        System.arraycopy(info, 0, in, 0, info.length);
        in[info.length] = 1;
        return Arrays.copyOf(mac.doFinal(in), len);
    }

    static String b64e(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static byte[] b64d(String s) { return Base64.getUrlDecoder().decode(s); }
}
