package com.chronos.bench;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Replays a standard FIX log file over a TCP connection to the Chronos FIX Gateway.
 * 
 * <p>
 * Usage:
 * {@code java -cp chronos-benchmarks.jar com.chronos.bench.FixLogReplayer [host] [port] [file]}
 * </p>
 */
public class FixLogReplayer {

    private static final Logger LOG = LoggerFactory.getLogger(FixLogReplayer.class);
    private static final byte SOH = 0x01;

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: FixLogReplayer <host> <port> <logfile> [speed_factor]");
            System.exit(1);
        }

        final String host = args[0];
        final int port = Integer.parseInt(args[1]);
        final String logFile = args[2];
        final double speedFactor = args.length > 3 ? Double.parseDouble(args[3]) : 1.0;

        System.out.println(">>> Starting FIX Replayer. Target: " + host + ":" + port + ", File: " + logFile);
        LOG.info("Starting FIX Replayer. Target: {}:{}, File: {}, Speed: {}", host, port, logFile, speedFactor);

        try (Socket socket = new Socket(host, port);
             OutputStream out = socket.getOutputStream();
             BufferedReader reader = new BufferedReader(new FileReader(logFile))) {

            LOG.info("Connected to {}:{}", host, port);

            String line;
            long count = 0;
            long startTimeMs = System.currentTimeMillis();

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                // convert '|' to SOH if needed (common in dev logs)
                String fixMessage = line.replace('|', (char) SOH);
                
                // Ensure it ends with SOH
                if (!fixMessage.endsWith("\u0001")) {
                     fixMessage += "\u0001";
                }

                byte[] bytes = fixMessage.getBytes(StandardCharsets.US_ASCII);
                out.write(bytes);
                
                // Simple flush every message to simulate distinct packets
                // In high throughput scenarios we might want to batch, but for replay correctness
                // we usually want 1 msg = 1 TCP write or closely packed.
                out.flush();

                count++;
                if (count % 1000 == 0) {
                    LOG.info("Replayed {} messages...", count);
                }

                // TODO: Implement sophisticated timing logic based on Tag 52 (SendingTime)
                // For now, simple busy-spin or sleep if speedFactor is very slow, 
                // but default is "blast at max speed" (limited by TCP flow control).
            }

            long durationMs = System.currentTimeMillis() - startTimeMs;
            LOG.info("Replay complete. Sent {} messages in {} ms ({} msg/sec)", 
                    count, durationMs, String.format("%.2f", count * 1000.0 / durationMs));

        } catch (IOException e) {
            System.err.println(">>> Error during replay: " + e.getMessage());
            LOG.error("Error during replay", e);
        }
    }
}
