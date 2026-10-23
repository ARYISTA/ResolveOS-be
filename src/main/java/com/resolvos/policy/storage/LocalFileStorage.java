package com.resolvos.policy.storage;

import com.resolvos.common.config.StorageProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Service
public class LocalFileStorage implements StorageService {

    private final Path baseDir;

    public LocalFileStorage(StorageProperties storageProperties) {
        this.baseDir = Paths.get(storageProperties.baseDir()).toAbsolutePath().normalize();
    }

    @Override
    public String store(UUID tenantId, UUID policyId, int version, String filename, byte[] bytes) throws IOException {
        Path targetDir = baseDir.resolve(tenantId.toString())
                .resolve(policyId.toString())
                .resolve("v" + version);
        Files.createDirectories(targetDir);

        String safeFilename = sanitizeFilename(filename);
        Path targetFile = targetDir.resolve(safeFilename);
        Files.write(targetFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        return targetFile.toString();
    }

    @Override
    public byte[] load(String filePath) throws IOException {
        Path path = Paths.get(filePath).normalize();
        if (!path.startsWith(baseDir)) {
            throw new SecurityException("Access to path outside base storage directory is forbidden");
        }
        return Files.readAllBytes(path);
    }

    @Override
    public boolean delete(String filePath) {
        try {
            Path path = Paths.get(filePath).normalize();
            if (path.startsWith(baseDir)) {
                return Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {}
        return false;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "document.pdf";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
