package com.resolvos.policy.storage;

import java.io.IOException;
import java.util.UUID;

public interface StorageService {
    String store(UUID tenantId, UUID policyId, int version, String filename, byte[] bytes) throws IOException;
    byte[] load(String filePath) throws IOException;
    boolean delete(String filePath);
}
