package io.github.nikhilvirdi.jhusk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
 *
 * <p><b>CI Guidance:</b> Whether {@code .jhusk/} should be gitignored or
 * cached in CI depends on what you want: gitignoring it means every CI run
 * starts fresh and only catches a regression if the property re-discovers
 * the failure on its own within that run's example budget: caching it
 * (e.g. as a CI cache keyed on a stable identifier) means a previously-found
 * failure is replayed and re-verified on every run, catching a regression
 * immediately even if the random budget wouldn't otherwise re-find it. Most
 * projects should gitignore {@code .jhusk/} in the repository itself (stored
 * failures are local debugging artifacts, not source) but consider caching
 * it as a CI build artifact if regression-catching consistency matters more
 * than a clean-slate run.
 *
 * <p>Most callers should reach this through {@link Property#withStorageDir(Path)} rather than
 * constructing or calling a {@code FailureStorage} directly; it is exposed as a standalone public
 * type for tooling that wants to inspect or manage a {@code .jhusk/} directory outside of running
 * a property (for example, a CI step that lists or clears stored failures).
 *
 * <p><b>Thread-safety:</b> a {@code FailureStorage} instance holds no mutable state beyond its
 * immutable {@code storageDir}, so the instance itself is safe to share across threads. {@link
 * #saveFailure(String, byte[])} writes to a temporary file in {@code storageDir} and atomically
 * renames it into place ({@link java.nio.file.StandardCopyOption#ATOMIC_MOVE}), so a concurrent
 * {@link #loadFailure(String)} will only ever observe a complete old buffer or a complete new one
 * — never a torn/partial one, even under heavy concurrent writes to the same property identity.
 * (An earlier version wrote directly via truncate-then-write, which is not atomic; empirically,
 * concurrent writes under that scheme corrupted roughly a third of concurrent reads in a stress
 * test — this is not a theoretical concern.) What atomicity does <em>not</em> give you: if two
 * writes to the same identity race, one of them still simply wins outright (last rename wins) —
 * there is no merging. Distinct identities or distinct storage directories are entirely unaffected
 * either way.
 */
public class FailureStorage {

    /**
     * Default directory name for storing persistent failure buffers.
     *
     * @since 1.2.0
     */
    public static final String DEFAULT_FAILURE_DIR_NAME = ".jhusk";

    private static final int RENAME_MAX_ATTEMPTS = 5;
    private static final long RENAME_RETRY_BACKOFF_MILLIS = 2;

    private final Path storageDir;

    /**
     * Constructs a FailureStorage using the default {@code .jhusk/} directory in the working directory.
     */
    public FailureStorage() {
        this(Paths.get(DEFAULT_FAILURE_DIR_NAME));
    }

    /**
     * Constructs a FailureStorage using a custom storage directory (useful for unit tests).
     *
     * @param storageDir the directory where failure files will be stored
     */
    public FailureStorage(Path storageDir) {
        this.storageDir = storageDir;
    }

    /**
     * Returns the directory this instance reads and writes failure buffers in.
     *
     * @return the configured storage directory
     */
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
        Path tempFile = null;
        try {
            Files.createDirectories(storageDir);
            Path filePath = getFilePath(propertyId);
            // Write to a temp file in the SAME directory, then atomically rename into place, so a
            // concurrent reader never observes a truncated-but-not-yet-rewritten intermediate
            // state. The temp file must be in storageDir (not a system temp dir) for the rename to
            // be a same-filesystem, and therefore atomic, move.
            tempFile = Files.createTempFile(storageDir, filePath.getFileName().toString(), ".tmp");
            Files.write(tempFile, shrunkBuffer);
            moveIntoPlaceWithRetry(tempFile, filePath);
        } catch (IOException e) {
            // Failure persistence is best-effort; log or swallow rather than crashing the test runner
            io.github.nikhilvirdi.jhusk.internal.ConsolidatedWarnings.record("save", propertyId, e.getMessage());
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best-effort cleanup of the orphaned temp file only
                }
            }
        }
    }

    /**
     * Renames {@code tempFile} onto {@code filePath} atomically, retrying briefly on failure.
     *
     * <p>POSIX {@code rename()} atomically replaces a target even while another process has it
     * open; NTFS does not, so a concurrent reader or writer can transiently block {@code
     * ATOMIC_MOVE} on Windows for a target that would otherwise succeed a moment later.
     * Empirically, under a 4-thread stress test (two writers racing the same identity, two
     * readers), this happened often enough to matter, not as a rare fluke. Each retried attempt is
     * still a single atomic rename — this loop tolerates a platform-specific transient contention
     * window, it doesn't weaken the atomicity guarantee itself.
     */
    private static void moveIntoPlaceWithRetry(Path tempFile, Path filePath) throws IOException {
        IOException lastFailure;
        int attemptsLeft = RENAME_MAX_ATTEMPTS;
        do {
            try {
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                lastFailure = e;
                try {
                    Thread.sleep(RENAME_RETRY_BACKOFF_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        } while (--attemptsLeft > 0);
        throw lastFailure;
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
            // Consistent with saveFailure/pruneFailure: best-effort, but still logged. Silently
            // returning empty here (as if no failure were stored) would hide a real problem --
            // e.g. a permissions error or a torn file from the concurrent-write race documented
            // above -- behind what looks like a clean, regression-free run.
            io.github.nikhilvirdi.jhusk.internal.ConsolidatedWarnings.record("load", propertyId, e.getMessage());
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
            io.github.nikhilvirdi.jhusk.internal.ConsolidatedWarnings.record("prune", propertyId, e.getMessage());
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
