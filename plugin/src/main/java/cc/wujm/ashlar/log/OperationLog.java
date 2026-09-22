// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.log;

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

    private final Path dataFolder;
    // Not final: logging.log-operations is hot (step8j-prompt.md) - /ashlar reload calls
    // setEnabled, which opens/closes writer under lock along with it.
    private boolean enabled;
    private final Logger logger;
    private final Object lock = new Object();
    private BufferedWriter writer;

    public OperationLog(Path dataFolder, boolean enabled, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        synchronized (lock) {
            this.enabled = enabled && openWriter();
        }
    }

    /** Opens {@link #writer} if not already open; returns whether it is open afterwards. Caller must hold {@link #lock}. */
    private boolean openWriter() {
        if (writer != null) {
            return true;
        }
        try {
            Files.createDirectories(dataFolder);
            Path logFile = dataFolder.resolve("operations.log");
            this.writer = Files.newBufferedWriter(
                    logFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to open operations.log for writing; operation logging is disabled", e);
            return false;
        }
    }

    /**
     * Applies a new {@code logging.log-operations} value ({@code /ashlar reload}, step8j-prompt.md):
     * opens {@link #writer} (appending to the same file) if turning logging on and it is not
     * already open, or closes it if turning it off. A failed open leaves logging disabled, same as
     * the constructor.
     */
    public void setEnabled(boolean enabled) {
        synchronized (lock) {
            if (enabled == this.enabled) {
                return;
            }
            if (enabled) {
                this.enabled = openWriter();
            } else {
                this.enabled = false;
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Failed to close operations.log cleanly", e);
                    }
                    writer = null;
                }
            }
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
