package com.miqroera.miqrokey.domain.crypto;

import java.util.Arrays;
import java.util.Base64;

public record VirtualKeyMaterial(String fullDisplayString, String publicKeyId, byte[] rawSecret, String displayPrefix,
        String lastFour, byte[] digest) {

    public static final String PREFIX = "mqk_live_";
    public static final int PUBLIC_KEY_ID_BYTES = 16;
    public static final int RAW_SECRET_BYTES = 32;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public VirtualKeyMaterial {
        rawSecret = rawSecret.clone();
        digest = digest.clone();
    }

    @Override
    public byte[] rawSecret() {
        return rawSecret.clone();
    }

    @Override
    public byte[] digest() {
        return digest.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof VirtualKeyMaterial that))
            return false;
        return fullDisplayString.equals(that.fullDisplayString) && publicKeyId.equals(that.publicKeyId)
                && Arrays.equals(rawSecret, that.rawSecret) && displayPrefix.equals(that.displayPrefix)
                && lastFour.equals(that.lastFour) && Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
        int result = fullDisplayString.hashCode();
        result = 31 * result + publicKeyId.hashCode();
        result = 31 * result + Arrays.hashCode(rawSecret);
        result = 31 * result + displayPrefix.hashCode();
        result = 31 * result + lastFour.hashCode();
        result = 31 * result + Arrays.hashCode(digest);
        return result;
    }

    @Override
    public String toString() {
        return "VirtualKeyMaterial[publicKeyId=" + publicKeyId + ", displayPrefix=" + displayPrefix + ", lastFour="
                + lastFour + ", digestPresent=true]";
    }
}
