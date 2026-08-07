package io.github.nikhilvirdi.jhusk.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * On-disk failure persistence manager for JHusk.
 *
 * <p><b>On-Disk Location:</b> Defaults to the {@code .jhusk/} directory relative to the
 * project's current working directory.
 *
 * <p><b>File Format Choice:</b> One binary file per property identity ({@code .jhusk/<sanitized-id>.bytes}).
 * <br><i>Justification:</i> Storing individual binary files per property avoids lock contention
 * on a single shared index file, permits clean atomic file creation/deletion (pruning), and allows
 * developers to inspect or delete individual stored failures without touching others.
 */
public class FailureStorage {

    private final Path storageDir;

    /**
     * Constructs a FailureStorage using the default {@code .jhusk/} directory in the working directory.
     */
    public FailureStorage() {
        this(Paths.get(".jhusk"));
    }

    /**
     * Constructs a FailureStorage using a custom storage directory (useful for unit tests).
     *
     * @param storageDir the directory where failure files will be stored
     */
    public FailureStorage(Path storageDir) {
        this.storageDir = storageDir;
    }

    public Path getStorageDir() {
        return storageDir;
    }

    /**
     * Saves a minimal shrunk byte buffer to disk for the given property identity.
     *
     * @param propertyId the property identity
     * @param shrunkBuffer the minimal failing byte buffer
     */
    public void saveFailure(String propertyId, byte[] shrunkBuffer) {
        try {
            Files.createDirectories(storageDir);
            Path filePath = getFilePath(propertyId);
            Files.write(filePath, shrunkBuffer);
        } catch (IOException e) {
            // Failure persistence is best-effort; log or swallow rather than crashing the test runner
            System.err.println("[JHusk Warning] Failed to save failure buffer for property '" + propertyId + "': " + e.getMessage());
        }
    }

    /**
     * Loads a stored failure byte buffer for the given property identity, if it exists.
     *
     * @param propertyId the property identity
     * @return an Optional containing the byte buffer, or empty if no failure is stored
     */
    public Optional<byte[]> loadFailure(String propertyId) {
        Path filePath = getFilePath(propertyId);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            return Optional.of(bytes);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Prunes (deletes) a stored failure file from disk when a property bug is fixed.
     *
     * @param propertyId the property identity
     */
    public void pruneFailure(String propertyId) {
        Path filePath = getFilePath(propertyId);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            System.err.println("[JHusk Warning] Failed to prune failure buffer for property '" + propertyId + "': " + e.getMessage());
        }
    }

    /**
     * Sanitizes a property identity string to make a safe filename.
     */
    private Path getFilePath(String propertyId) {
        String sanitized = propertyId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return storageDir.resolve(sanitized + ".bytes");
    }
}
