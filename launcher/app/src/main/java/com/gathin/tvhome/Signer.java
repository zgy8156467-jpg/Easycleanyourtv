package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Trust anchor for the update channel: verifies the manifest signature and that a downloaded APK
 * really was signed by this project.
 *
 * <p>The private key never leaves the release machine; only this public key is compiled in. The
 * manifest signature covers {@code v2\n<channel>\n<versionCode>\n<sha256>\n<size>} — see
 * {@link #canonical}. Deliberately no version name: it is display-only and signing it would drag
 * in code-point/byte normalisation for no security gain.
 *
 * <p>Everything here fails closed. A missing, malformed or unverifiable signature means "no
 * update", never "update anyway".
 */
final class Signer {

    private static final String TAG = "TvHome.Signer";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * ECDSA P-256 public key, X.509 SubjectPublicKeyInfo, base64.
     *
     * <p>Generate a key pair with {@code tools/sign-release.py --init}; paste the printed public key here
     * and keep the private key off every machine that is not the release box. While this still says
     * REPLACE_ME every check fails, which is the safe direction — but it also means no TV will ever
     * see an update, so fill it in before shipping.
     */
    private static final String PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ43kifJtC9ZPAxqMbPAbd746cxuFX9+GwORAjs7fJKypigFuOWZt+JTA0UeUvfJARNZ1IsOYFXCM9KcjHmxIAQ==";

    private Signer() {
    }

    /** The exact bytes the release tool signs. Keep in step with tools/sign-release.py. */
    static String canonical(String channel, int code, String sha256, long size) {
        return "v2\n" + channel + "\n" + code + "\n"
                + sha256.toLowerCase(java.util.Locale.US) + "\n" + size;
    }

    static boolean verify(String canonical, String sig) {
        if (PUBLIC_KEY.startsWith("REPLACE_ME") || sig == null || sig.isEmpty()) return false;
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey());
            verifier.update(canonical.getBytes(UTF8));
            // base64url, padding optional: '+' and '/' would not survive a query string.
            int flags = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
            return verifier.verify(Base64.decode(sig, flags));
        } catch (Exception e) {
            Log.w(TAG, "signature check failed", e);
            return false;
        }
    }

    private static PublicKey publicKey() throws Exception {
        byte[] der = Base64.decode(PUBLIC_KEY, Base64.DEFAULT);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * Whether {@code apk} carries the same signing certificate as the running app. Android already
     * refuses to install across signatures ({@code INSTALL_FAILED_UPDATE_INCOMPATIBLE}); this runs
     * first so the failure shows up as a readable message instead of the firmware's dialog.
     */
    static boolean sameSigner(Context context, File apk) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo self = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            PackageInfo other = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES);
            return self != null && self.signatures != null && self.signatures.length > 0
                    && other != null && other.signatures != null && other.signatures.length > 0
                    && self.signatures[0].equals(other.signatures[0]);
        } catch (Exception e) {
            Log.w(TAG, "signer comparison failed", e);
            return false;
        }
    }
}
