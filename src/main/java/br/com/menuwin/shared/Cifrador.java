package br.com.menuwin.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM para segredos guardados no banco (tokens de gateway).
 * Formato gravado: Base64(IV de 12 bytes + texto cifrado com a tag).
 * GCM também autentica: texto adulterado ou chave errada falha na decifragem.
 */
@Component
public class Cifrador {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey chave;
    private final SecureRandom random = new SecureRandom();

    public Cifrador(@Value("${menuwin.crypto.chave}") String chaveBase64) {
        byte[] bytes = Base64.getDecoder().decode(chaveBase64);
        if (bytes.length != 32) {
            throw new IllegalStateException("menuwin.crypto.chave precisa ter 32 bytes (AES-256) em Base64");
        }
        this.chave = new SecretKeySpec(bytes, "AES");
    }

    public String cifrar(String texto) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cifrado = cipher.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + cifrado.length).put(iv).put(cifrado).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar", e);
        }
    }

    public String decifrar(String gravado) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(gravado));
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] cifrado = new byte[buffer.remaining()];
            buffer.get(cifrado);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cifrado), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Falha ao decifrar (chave trocada ou dado adulterado)", e);
        }
    }
}
