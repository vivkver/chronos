package com.chronos.gateway.fix.integration;

import com.chronos.gateway.fix.FixGatewayMain;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("wire-to-wire")
public class WireToWireTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void verifyFixLogon() throws Exception {
        // Start the Gateway in a separate thread
        Thread gatewayThread = new Thread(() -> {
            try {
                System.out.println("TEST: Starting Gateway...");
                FixGatewayMain.main(new String[] {});
            } catch (Exception e) {
                System.err.println("TEST: Gateway failed to start!");
                e.printStackTrace();
            }
        });
        gatewayThread.start();

        // Wait for gateway to start
        Thread.sleep(5000);

        // Connect client
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", 9876))) {
            client.configureBlocking(true);

            // Send Logon
            String logon = "8=FIX.4.4\u00019=80\u000135=A\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                    +
                    "98=0\u0001108=30\u000110=000\u0001";
            client.write(ByteBuffer.wrap(logon.getBytes(StandardCharsets.US_ASCII)));

            // Read Response
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            int read = client.read(buffer);
            assertTrue(read > 0, "Should have received a response");

            String response = new String(buffer.array(), 0, read, StandardCharsets.US_ASCII);
            assertTrue(response.contains("35=A"), "Response should be a LOGON");

            System.out.println("TEST: LOGON Successful. Sending NewOrderSingle...");

            // Send NewOrderSingle (Length 9=135 approx)
            String newOrder = "8=FIX.4.4\u00019=135\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=2\u000152=20240101-12:00:01\u0001"
                    +
                    "11=CLORD1\u000155=GOOGL\u000154=1\u000160=20240101-12:00:01\u000138=100\u000140=2\u000144=150.00\u000110=000\u0001";

            client.write(ByteBuffer.wrap(newOrder.getBytes(StandardCharsets.US_ASCII)));

            client.configureBlocking(false);
            Thread.sleep(1000);

            buffer.clear();
            int bytes = client.read(buffer);
            if (bytes > 0) {
                String msg = new String(buffer.array(), 0, bytes, StandardCharsets.US_ASCII);
                if (msg.contains("35=3")) {
                    System.err.println("TEST: Received Reject! " + msg);
                    throw new RuntimeException("Received Reject: " + msg);
                }
            } else {
                System.out.println("TEST: No immediate Reject. Order sent.");
            }
        }

        // Cleanup (Gateway shutdown hook should handle the rest, but we interrupt for
        // speed)
        gatewayThread.interrupt();
    }
}
