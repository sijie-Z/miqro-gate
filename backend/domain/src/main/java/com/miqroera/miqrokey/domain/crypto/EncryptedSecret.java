package com.miqroera.miqrokey.domain.crypto;

import java.util.Arrays;

public record EncryptedSecret(byte[] ciphertext, byte[] nonce, String keyVersion) {

    public EncryptedSecret {
        ciphertext = ciphertext.clone();
        nonce = nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof EncryptedSecret that))
            return false;
        return Arrays.equals(ciphertext, that.ciphertext) && Arrays.equals(nonce, that.nonce)
                && keyVersion.equals(that.keyVersion);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(ciphertext);
        result = 31 * result + Arrays.hashCode(nonce);
        result = 31 * result + keyVersion.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "EncryptedSecret[keyVersion=" + keyVersion + ", ciphertextSize=" + ciphertext.length + ", nonceSize="
                + nonce.length + "]";
    }
}
