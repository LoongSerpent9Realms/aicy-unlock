package dev.loong.aicyunlock;

import android.content.Context;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * API Key 落盘前的加密。
 *
 * <p>Android 上没引第三方库，所以用 AES/GCM + 由设备标识派生的密钥：密文只在
 * 本机可解，备份出去也没用。密钥派生用 ANDROID_ID 而不是随机 key，是因为
 * Aicy 进程要通过 {@link ConfigProvider} 读明文，派生式更简单（无需另存密钥）。
 *
 * <p>格式：{@code enc:v1:<base64(iv[12] + ciphertext)>}。老的无前缀值按明文处理，
 * 方便平滑升级。
 */
final class SecretStore {

    private static final String PREFIX = "enc:v1:";
    private static final String SALT = "dev.loong.aicyunlock|tts|v1";

    private SecretStore() {
    }

    static String seal(Context context, String plain) {
        if (plain == null || plain.isEmpty()) return "";
        if (plain.startsWith(PREFIX)) return plain;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(context), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] joined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, joined, 0, iv.length);
            System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(joined);
        } catch (Throwable ignored) {
            return plain; // 加密不可用时退回明文，功能优先
        }
    }

    static String open(Context context, String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!stored.startsWith(PREFIX)) return stored;
        try {
            byte[] joined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[12];
            System.arraycopy(joined, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(context), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(joined, 12, joined.length - 12);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return ""; // 解不开就当没配，避免拿着一堆乱码去请求
        }
    }

    private static SecretKeySpec key(Context context) throws Exception {
        String id;
        try {
            id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
            id = "unknown";
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((SALT + "|" + id).getBytes(StandardCharsets.UTF_8));
        byte[] aes = new byte[16];
        System.arraycopy(digest, 0, aes, 0, 16);
        return new SecretKeySpec(aes, "AES");
    }
}
