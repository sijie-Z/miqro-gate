package com.miqroera.miqrokey.domain.crypto;

import java.util.UUID;

public interface VirtualKeyCrypto {

    VirtualKeyMaterial generate();

    boolean validateConstantTime(String publicKeyId, byte[] rawSecret, byte[] expectedDigest, UUID tenantId);

    byte[] computeDigest(String publicKeyId, byte[] rawSecret, UUID tenantId);

    String activeKeyVersion();
}
