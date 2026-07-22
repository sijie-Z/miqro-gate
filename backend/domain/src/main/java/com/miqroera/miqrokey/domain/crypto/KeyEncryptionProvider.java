package com.miqroera.miqrokey.domain.crypto;

import java.util.UUID;

public interface KeyEncryptionProvider {

    EncryptedSecret encrypt(byte[] plaintext, UUID tenantId, UUID credentialId);

    byte[] decrypt(EncryptedSecret encrypted, UUID tenantId, UUID credentialId);

    EncryptedSecret reEncrypt(EncryptedSecret encrypted, UUID tenantId, UUID credentialId);

    String activeKeyVersion();
}
