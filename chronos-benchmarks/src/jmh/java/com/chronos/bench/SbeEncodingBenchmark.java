package com.chronos.bench;

import com.chronos.gateway.fix.FixParser;
import com.chronos.gateway.fix.FixToSbeEncoder;
import com.chronos.schema.sbe.MessageHeaderEncoder;
import com.chronos.schema.sbe.NewOrderSingleEncoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: Zero-allocation SBE encoding vs simulated string-based FIX
 * encoding.
 *
 * <p>
 * Proves that the zero-allocation FIX→SBE hot path is significantly faster than
 * traditional string-based FIX parsing (as used by QuickFIX/J and similar
 * libraries).
 * </p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgs = { "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UseZGC", "-XX:+ZGenerational" })
public class SbeEncodingBenchmark {

    private byte[] fixMessageBytes;
    private UnsafeBuffer fixBuffer;
    private UnsafeBuffer sbeOutputBuffer;
    private FixParser parser;
    private FixToSbeEncoder encoder;

    // Simulated string-based approach buffers
    private String fixMessageString;

    @Setup(Level.Trial)
    public void setup() {
        // Realistic FIX 4.4 NewOrderSingle message
        final String fixMsg = "8=FIX.4.4\u00019=120\u000135=D\u000149=12345\u0001" +
                "11=100001\u000155=AAPL\u000154=1\u000144=15050000000\u0001" +
                "38=100\u000140=2\u000110=128\u0001";

        fixMessageBytes = fixMsg.getBytes(StandardCharsets.US_ASCII);
        fixBuffer = new UnsafeBuffer(fixMessageBytes);
        sbeOutputBuffer = new UnsafeBuffer(new byte[FixToSbeEncoder.encodedSize()]);
        parser = new FixParser();
        encoder = new FixToSbeEncoder();
        fixMessageString = fixMsg;
    }

    /**
     * Zero-allocation path: FixParser → FixToSbeEncoder → SBE bytes in buffer.
     * This is the CHRONOS hot path. No String objects created.
     */
    @Benchmark
    public int chronosZeroAllocEncoding(final Blackhole bh) {
        parser.parse(fixBuffer, 0, fixMessageBytes.length);
        return encoder.encodeNewOrderSingle(parser, sbeOutputBuffer, 0, 1);
    }

    /**
     * Simulated string-based encoding (similar to QuickFIX/J approach).
     * Creates String objects for each field — triggers GC pressure.
     */
    @Benchmark
    public int stringBasedEncoding(final Blackhole bh) {
        // Split by SOH (this allocates a String[] array)
        final String[] fields = fixMessageString.split("\u0001");

        long orderId = 0;
        long price = 0;
        int quantity = 0;
        byte side = 0;

        // Parse each field (creates substring objects)
        for (final String field : fields) {
            final int eqIdx = field.indexOf('=');
            if (eqIdx < 0)
                continue;
            final String tagStr = field.substring(0, eqIdx);
            final String valueStr = field.substring(eqIdx + 1);
            final int tag = Integer.parseInt(tagStr);

            switch (tag) {
                case 11 -> orderId = Long.parseLong(valueStr);
                case 44 -> price = Long.parseLong(valueStr);
                case 38 -> quantity = Integer.parseInt(valueStr);
                case 54 -> side = (byte) valueStr.charAt(0);
            }
        }

        // Write to SBE buffer (this part is the same)
        final int offset = MessageHeaderEncoder.ENCODED_LENGTH;
        sbeOutputBuffer.putLong(offset, orderId);
        sbeOutputBuffer.putLong(offset + 8, price);
        sbeOutputBuffer.putInt(offset + 36, quantity);
        sbeOutputBuffer.putByte(offset + 40, side);

        return offset + NewOrderSingleEncoder.BLOCK_LENGTH;
    }
}
