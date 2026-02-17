package com.chronos.bench;

import com.chronos.gateway.fix.FixParser;
import com.paritytrading.philadelphia.FIXMessage;
import com.paritytrading.philadelphia.FIXMessageOverflowException;
import com.paritytrading.philadelphia.FIXValueOverflowException;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;
import quickfix.InvalidMessage;
import quickfix.Message;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class FixBenchmark {

    /**
     * Standard FIX 4.4 NewOrderSingle message with correct body length and checksum.
     * 
     * Message breakdown:
     * 8=FIX.4.4        | BeginString (protocol version)
     * 9=102            | BodyLength (from tag 35 to tag 40 inclusive, excluding checksum)
     * 35=D             | MsgType (D = NewOrderSingle)
     * 49=CLIENT1       | SenderCompID
     * 56=CHRONOS       | TargetCompID
     * 34=1             | MsgSeqNum
     * 52=20231023-12:00:00.000 | SendingTime
     * 11=ORD001        | ClOrdID (Client Order ID)
     * 55=AAPL          | Symbol
     * 54=1             | Side (1=Buy, 2=Sell)
     * 38=100           | OrderQty
     * 44=150.50        | Price
     * 40=2             | OrdType (1=Market, 2=Limit)
     * 10=144           | CheckSum (3-digit checksum modulo 256)
     * 
     * Body length calculation: Count all characters from after 9=XXX\u0001 to before 10=XXX\u0001
     * Checksum calculation: Sum of all bytes from start to before 10=XXX\u0001, modulo 256
     */
    private static final String FIX_MSG_STR = "8=FIX.4.4\u00019=102\u000135=D\u000149=CLIENT1\u000156=CHRONOS\u000134=1\u000152=20231023-12:00:00.000\u000111=ORD001\u000155=AAPL\u000154=1\u000138=100\u000144=150.50\u000140=2\u000110=144\u0001";

    
    private byte[] fixMsgBytes;
    private UnsafeBuffer chronosBuffer;
    private FixParser chronosParser;
    
    // Philadelphia setup
    private FIXMessage philadelphiaMessage;
    private ByteBuffer philadelphiaBuffer;

    @Setup
    public void setup() {
        fixMsgBytes = FIX_MSG_STR.getBytes(StandardCharsets.US_ASCII);
        chronosBuffer = new UnsafeBuffer(fixMsgBytes);
        chronosParser = new FixParser();
        
        // Philadelphia setup
        philadelphiaMessage = new FIXMessage(32, 32);
        philadelphiaBuffer = ByteBuffer.wrap(fixMsgBytes);
    }

    /**
     * CHRONOS zero-copy FIX parser benchmark.
     * 
     * What it measures:
     * - Extracts tag-value pairs into pre-allocated arrays (zero allocation)
     * - Returns field count as int (no object construction)
     * - No validation, no string creation, pure byte-level parsing
     * - Operates directly on DirectBuffer (zero-copy)
     * 
     * This is optimized for ultra-low latency where you only need to extract
     * specific fields (e.g., price, quantity) without full message validation.
     */
    @Benchmark
    public int chronosParse() {
        return chronosParser.parse(chronosBuffer, 0, fixMsgBytes.length);
    }

    /**
     * QuickFIX/J full parsing with validation (industry standard).
     * 
     * What it measures:
     * - Full Message object construction (allocates objects)
     * - Validates body length and checksum
     * - Creates String objects for all field values
     * - Builds internal field map with validation
     * - Suitable for production systems requiring full FIX compliance
     * 
     * Expected to be ~10-20x slower than Chronos due to:
     * 1. Object allocation (Message, FieldMap, String objects)
     * 2. Validation overhead (body length, checksum)
     * 3. HashMap operations for field storage
     */
    @Benchmark
    public Message quickfixParse() throws InvalidMessage {
        return new Message(FIX_MSG_STR, true);
    }
    
    /**
     * QuickFIX/J parsing without validation (faster path).
     * 
     * What it measures:
     * - Message object construction without validation
     * - Still allocates objects and creates Strings
     * - Skips body length and checksum validation
     * 
     * Expected to be ~5-10x slower than Chronos due to:
     * 1. Object allocation (still creates Message and Strings)
     * 2. HashMap operations
     * 3. No zero-copy optimization
     */
    @Benchmark
    public Message quickfixParseNoValidation() throws InvalidMessage {
        return new Message(FIX_MSG_STR, false);
    }
    
    /**
     * Philadelphia FIX parser benchmark.
     * 
     * What it measures:
     * - Parses FIX message from ByteBuffer into FIXMessage object
     * - Low allocation design (reuses FIXMessage object)
     * - Non-blocking I/O optimized
     * - Zero-alloc on RX/TX paths
     * 
     * Expected to be faster than QuickFIX/J but slower than Chronos:
     * 1. Creates FIXMessage object (some allocation)
     * 2. No HashMap operations (uses direct field access)
     * 3. Optimized for low latency but not zero-copy like Chronos
     */
    @Benchmark
    public FIXMessage philadelphiaParse() throws FIXMessageOverflowException, FIXValueOverflowException {
        philadelphiaBuffer.rewind();
        philadelphiaMessage.get(philadelphiaBuffer);
        return philadelphiaMessage;
    }
}
