package com.chronos.gateway.fix.integration;

import com.chronos.gateway.fix.FixGatewayMain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Poison Pill / Fuzzing Integration Test.
 *
 * Verifies that the Gateway survives malformed, malicious, or garbage inputs.
 * The Gateway should either:
 * 1. Reject the message (business reject)
 * 2. Drop the connection (protocol violation)
 * 3. Ignore the garbage
 *
 * Crucially, it must NOT crash or hang.
 */
@Timeout(30)
public class PoisonPillIntegrationTest {

    private static Thread gatewayThread;
    private SocketChannel client;

    @BeforeAll
    public static void startGateway() throws InterruptedException {
        System.out.println("TEST: Starting Gateway thread...");
        gatewayThread = new Thread(() -> {
            try {
                FixGatewayMain.main(new String[] {});
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        gatewayThread.setName("Gateway-Main");
        gatewayThread.setDaemon(true);
        gatewayThread.start();

        System.out.println("TEST: Waiting 5s for Gateway to bind...");
        Thread.sleep(5000);
    }

    @AfterAll
    public static void stopGateway() {
        if (gatewayThread != null && gatewayThread.isAlive()) {
            System.out.println("TEST: Stopping Gateway...");
            gatewayThread.interrupt();
            try {
                gatewayThread.join(2000);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    @BeforeEach
    public void setupClient() throws Exception {
        client = SocketChannel.open();
        client.configureBlocking(true);
        boolean connected = false;

        // Retry connection logic
        for (int i = 0; i < 5; i++) {
            try {
                client.connect(new InetSocketAddress("localhost", 9876));
                connected = true;
                break;
            } catch (IOException e) {
                System.out.println("TEST: Failed to connect (" + ignored + "), retrying...");
                Thread.sleep(1000);
            }
        }

        if (!connected) {
            fail("Failed to connect to Gateway after retries");
        }
    }

    @AfterEach
    public void tearDownClient() throws IOException {
        if (client != null && client.isOpen()) {
            try {
                client.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    @Test
    public void testGarbageData() throws Exception {
        System.out.println("TEST: Sending random garbage bytes...");
        byte[] garbage = new byte[1024];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (Math.random() * 255);
        }
        client.write(ByteBuffer.wrap(garbage));

        // Assert: Gateway should stay alive and accept next connection or handle this
        // gracefully.
        verifyGatewayAlive();
    }

    @Test
    public void testMassiveBodyLength() throws Exception {
        System.out.println("TEST: Sending massive BodyLength claim...");
        // Claim 1GB body length
        String maliciousMsg = "8=FIX.4.4\u00019=1073741824\u000135=A\u0001...";
        client.write(ByteBuffer.wrap(maliciousMsg.getBytes(StandardCharsets.US_ASCII)));

        verifyGatewayAlive();
    }

    @Test
    public void testNegativeBodyLength() throws Exception {
        System.out.println("TEST: Sending negative BodyLength...");
        String maliciousMsg = "8=FIX.4.4\u00019=-50\u000135=A\u0001...";
        client.write(ByteBuffer.wrap(maliciousMsg.getBytes(StandardCharsets.US_ASCII)));

        verifyGatewayAlive();
    }

    @Test
    public void testincompleteTags() throws Exception {
        System.out.println("TEST: Sending incomplete tags...");
        String maliciousMsg = "8=FIX.4.4\u00019=10\u000135="; // Cut off mid-tag
        client.write(ByteBuffer.wrap(maliciousMsg.getBytes(StandardCharsets.US_ASCII)));

        Thread.sleep(500);
        verifyGatewayAlive();
    }

    @Test
    public void testSqlInjectionStrings() throws Exception {
        System.out.println("TEST: Sending SQL Injection pattern...");
        String logon = "8=FIX.4.4\u00019=80\u000135=A\u000149=BADCLIENT\u000156=CHORONS' OR 1=1; DROP TABLE orders; --\u000134=1\u000152=20240101-00:00:00\u000198=0\u0001108=30\u000110=000\u0001";

        client.write(ByteBuffer.wrap(logon.getBytes(StandardCharsets.US_ASCII)));

        verifyGatewayAlive();
    }

    private void verifyGatewayAlive() {
        try {
            Thread.sleep(500);
            try (SocketChannel probe = SocketChannel.open()) {
                probe.configureBlocking(true);
                probe.connect(new InetSocketAddress("localhost", 9876));
                assertTrue(probe.isConnected(), "Gateway should accept new connections");
            }
        } catch (Exception e) {
            fail("Gateway appears dead: " + e.getMessage());
        }
    }

    // Helper to ignore
    private static final String ignored = "";
}
