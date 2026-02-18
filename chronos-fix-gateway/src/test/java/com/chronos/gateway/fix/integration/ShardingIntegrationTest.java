package com.chronos.gateway.fix.integration;

import com.chronos.gateway.fix.FixGatewayMain;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(60)
public class ShardingIntegrationTest {

    private static Thread gatewayThread;
    private static Aeron aeron;
    private static Subscription shard0Subscription;
    private static Subscription shard1Subscription;

    @BeforeAll
    public static void startGateway() throws Exception {
        System.out.println("TEST: Starting Gateway...");
        gatewayThread = new Thread(() -> {
            try {
                FixGatewayMain.main(new String[] {});
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        gatewayThread.setName("Gateway-Sharding-Test");
        gatewayThread.setDaemon(true);
        gatewayThread.start();

        // Wait for Gateway to initialize and set directory
        System.out.println("TEST: Waiting for Gateway init...");
        Thread.sleep(5000);

        String aeronDir = FixGatewayMain.TEST_AERON_DIR_NAME;
        if (aeronDir == null) {
            fail("Gateway did not set TEST_AERON_DIR_NAME");
        }
        System.out.println("TEST: Connecting to Aeron at " + aeronDir);

        // Connect to the same Aeron instance (shared memory)
        Aeron.Context ctx = new Aeron.Context().aeronDirectoryName(aeronDir);
        aeron = Aeron.connect(ctx);

        // Subscribe to Shard 0 (Stream 1001) and Shard 1 (Stream 1002)
        shard0Subscription = aeron.addSubscription("aeron:ipc", 1001);
        shard1Subscription = aeron.addSubscription("aeron:ipc", 1002);

        System.out.println("TEST: Subscriptions added.");
    }

    @AfterAll
    public static void stopGateway() {
        if (aeron != null) {
            aeron.close();
        }
        if (gatewayThread != null) {
            gatewayThread.interrupt();
            try {
                gatewayThread.join(2000);
            } catch (InterruptedException e) {
            }
        }
    }

    @Test
    public void testShardingRouting() throws Exception {
        try (SocketChannel client = SocketChannel.open()) {
            client.configureBlocking(true);
            client.connect(new InetSocketAddress("localhost", 9876));

            // 1. Send Logon (required before orders)
            // 8=FIX.4.4|9=...|35=A|...
            // Minimal Logon for session parsing
            String logon = "8=FIX.4.4\u00019=72\u000135=A\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-00:00:00\u000198=0\u0001108=30\u000110=000\u0001";
            client.write(ByteBuffer.wrap(logon.getBytes(StandardCharsets.US_ASCII)));
            Thread.sleep(500);

            // 2. Send AAPL Order (Shard 0)
            // 35=D, 55=AAPL -> Should go to Stream 1001
            String orderAAPL = "8=FIX.4.4\u00019=100\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=2\u000152=20240101-00:00:01\u000111=ID1\u000155=AAPL\u000154=1\u000138=100\u000140=1\u000110=000\u0001";
            client.write(ByteBuffer.wrap(orderAAPL.getBytes(StandardCharsets.US_ASCII)));

            // Verify Shard 0 received it
            assertMessageReceived(shard0Subscription, "Shard 0 (AAPL)");
            // Verify Shard 1 did NOT receive it
            assertNoMessageReceived(shard1Subscription, "Shard 1 (AAPL check)");

            // 3. Send GOOG Order (Shard 1)
            // 35=D, 55=GOOG -> Should go to Stream 1002
            String orderGOOG = "8=FIX.4.4\u00019=100\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=3\u000152=20240101-00:00:02\u000111=ID2\u000155=GOOG\u000154=1\u000138=100\u000140=1\u000110=000\u0001";
            client.write(ByteBuffer.wrap(orderGOOG.getBytes(StandardCharsets.US_ASCII)));

            // Verify Shard 1 received it
            assertMessageReceived(shard1Subscription, "Shard 1 (GOOG)");
            // Verify Shard 0 did NOT receive it (assuming no other traffic)
            assertNoMessageReceived(shard0Subscription, "Shard 0 (GOOG check)");

        }
    }

    private void assertMessageReceived(Subscription sub, String description) {
        final AtomicInteger fragments = new AtomicInteger(0);
        FragmentHandler handler = (buffer, offset, length, header) -> fragments.incrementAndGet();

        // Poll for up to 3 seconds
        long deadline = System.currentTimeMillis() + 3000;
        SleepingMillisIdleStrategy idle = new SleepingMillisIdleStrategy(1);

        while (System.currentTimeMillis() < deadline) {
            if (sub.poll(handler, 10) > 0) {
                // Found it
                System.out.println("TEST: Message received on " + description);
                return;
            }
            idle.idle();
        }
        fail("Timed out waiting for message on " + description);
    }

    private void assertNoMessageReceived(Subscription sub, String description) {
        final AtomicInteger fragments = new AtomicInteger(0);
        FragmentHandler handler = (buffer, offset, length, header) -> fragments.incrementAndGet();

        // Poll for 500ms to ensure silence
        long deadline = System.currentTimeMillis() + 500;
        SleepingMillisIdleStrategy idle = new SleepingMillisIdleStrategy(1);

        while (System.currentTimeMillis() < deadline) {
            if (sub.poll(handler, 10) > 0) {
                fail("Unexpected message received on " + description);
            }
            idle.idle();
        }
        System.out.println("TEST: No message on " + description + " (As Expected)");
    }
}
