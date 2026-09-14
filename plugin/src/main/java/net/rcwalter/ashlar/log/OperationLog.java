// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Appends one line per completed RPC operation to
 * {@code plugins/Ashlar/operations.log}. Format:
 * {@code <ISO-8601 instant> | <ip> | <method> | <ok|error> | <blocksChanged>}.
 *
 * <p>This may be called from the WebSocket thread; it is not Bukkit API and
 * does not need to go through {@code MainThread}. Writes are synchronized
 * and flushed immediately so the log survives an unclean shutdown.
 */
public final class OperationLog {

    private final boolean enabled;
    private final Logger logger;
    private final Object lock = new Object();
    private BufferedWriter writer;

    public OperationLog(Path dataFolder, boolean enabled, Logger logger) {
        this.enabled = enabled;
        this.logger = logger;
        if (!enabled) {
            return;
        }
        try {
            Files.createDirectories(dataFolder);
            Path logFile = dataFolder.resolve("operations.log");
            this.writer = Files.newBufferedWriter(
                    logFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to open operations.log for writing; operation logging is disabled", e);
        }
    }

    public void append(String ip, String method, String result, long blocksChanged) {
        if (!enabled || writer == null) {
            return;
        }
        String line = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                + " | " + ip
                + " | " + method
                + " | " + result
                + " | " + blocksChanged;
        synchronized (lock) {
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to append to operations.log", e);
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to close operations.log cleanly", e);
                }
            }
        }
    }
}
