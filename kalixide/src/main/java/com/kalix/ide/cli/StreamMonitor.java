package com.kalix.ide.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Monitors a process output stream, invoking a per-line callback and
 * accumulating the full stream content.
 */
public class StreamMonitor {

    private static final Logger logger = LoggerFactory.getLogger(StreamMonitor.class);

    private final InputStream inputStream;
    private final Consumer<String> lineCallback;

    /**
     * Creates a new StreamMonitor.
     *
     * @param inputStream the input stream to monitor (stdout or stderr)
     * @param lineCallback callback for each line of output (can be null)
     */
    public StreamMonitor(InputStream inputStream, Consumer<String> lineCallback) {
        this.inputStream = inputStream;
        this.lineCallback = lineCallback;
    }

    /**
     * Starts monitoring the stream asynchronously.
     *
     * @param executorService the executor service to run the monitoring task
     * @return a CompletableFuture that completes with the full stream content when the stream is closed
     */
    public CompletableFuture<String> startMonitoring(ExecutorService executorService) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder fullOutput = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    fullOutput.append(line).append(System.lineSeparator());

                    if (lineCallback != null) {
                        try {
                            lineCallback.accept(line);
                        } catch (Exception e) {
                            // Don't let callback exceptions stop stream monitoring
                            logger.warn("Error in line callback", e);
                        }
                    }
                }

            } catch (Exception e) {
                // Log the error but return what we have so far
                logger.warn("Error reading stream", e);
            }

            return fullOutput.toString();
        }, executorService);
    }
}
