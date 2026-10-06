package it.fototimeline.google;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Cifra i refresh token di Google prima di salvarli: AES-256-GCM, IV casuale
 * di 12 byte, l'utente come dato associato (un token copiato su un altro
 * utente non si decifra). Testo salvato: Base64 di IV + cifrato + tag.
 *
 * <p>La chiave è lo SHA-256 di {@code FOTOTIMELINE_GOOGLE_CHIAVE}; se manca,
 * di una stringa fissa più il client secret. Cambiando chiave (o secret, senza
 * chiave) i token salvati non si leggono più: ognuno ricollega Google.
 */
@Component
class CifraturaToken {

    private static final SecureRandom CASO = new SecureRandom();
    private static final int IV = 12;
    private static final int TAG = 128;

    private final SecretKeySpec chiave;

    CifraturaToken(GoogleProperties properties) {
        String base = properties.chiave() != null ? properties.chiave()
                : properties.clientSecret() != null ? "fototimeline/google/refresh-token/" + properties.clientSecret()
                : null;
        this.chiave = base == null ? null : new SecretKeySpec(sha256(base), "AES");
    }

    String cifra(String testo, String utente) {
        richiedeChiave();
        try {
            byte[] iv = new byte[IV];
            CASO.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, chiave, new GCMParameterSpec(TAG, iv));
            c.updateAAD(utente.getBytes(StandardCharsets.UTF_8));
            byte[] cifrato = c.doFinal(testo.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(IV + cifrato.length).put(iv).put(cifrato).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cifratura non riuscita", e);
        }
    }

    /** Il testo in chiaro; IllegalArgumentException se la chiave è un'altra o il testo è stato toccato. */
    String decifra(String cifrato, String utente) {
        richiedeChiave();
        try {
            byte[] tutto = Base64.getDecoder().decode(cifrato);
            if (tutto.length <= IV) {
                throw new IllegalArgumentException("token cifrato troppo corto");
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, chiave, new GCMParameterSpec(TAG, tutto, 0, IV));
            c.updateAAD(utente.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(tutto, IV, tutto.length - IV), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("token non decifrabile (chiave cambiata?)", e);
        }
    }

    private void richiedeChiave() {
        if (chiave == null) {
            throw new IllegalStateException("Google non è configurato");
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
