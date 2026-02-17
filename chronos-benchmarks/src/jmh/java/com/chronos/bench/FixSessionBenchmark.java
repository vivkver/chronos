package com.chronos.bench;

import com.chronos.gateway.fix.FixParser;
import com.chronos.gateway.fix.FixMessageBuilder;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms2G", "-Xmx2G"})
 @State(Scope.Benchmark)
public class FixSessionBenchmark {

    private UnsafeBuffer parseBuffer;
    private UnsafeBuffer buildBuffer;
    
    private FixParser parser;
    private FixMessageBuilder builder;
    
    private byte[] logonMessage;
    private byte[] heartbeatMessage;
    private byte[] newOrderMessage;
    
    private int currentSeqNum;
    
    @Setup
    public void setup() {
        parseBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(2048));
        buildBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(2048));
        
        parser = new FixParser();
        builder = new FixMessageBuilder();
        
        logonMessage = createLogonMessage();
        heartbeatMessage = createHeartbeatMessage();
        newOrderMessage = createNewOrderMessage();
        
        currentSeqNum = 1;
    }
    
    private byte[] createLogonMessage() {
        final int length = builder.buildLogon(
                buildBuffer, 0,
                "CLIENT", "CHRONOS", 1, 30);
        
        final byte[] msg = new byte[length];
        buildBuffer.getBytes(0, msg);
        return msg;
    }
    
    private byte[] createHeartbeatMessage() {
        final int length = builder.buildHeartbeat(
                buildBuffer, 0,
                "CLIENT", "CHRONOS", 2, null);
        
        final byte[] msg = new byte[length];
        buildBuffer.getBytes(0, msg);
        return msg;
    }
    
    private byte[] createNewOrderMessage() {
        String msg = "8=FIX.4.4\u00019=150\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=3\u000152=20260214-12:00:00\u0001" +
                     "11=ORDER123\u000121=1\u000155=AAPL\u000154=1\u000160=20260214-12:00:00\u000138=100\u000140=2\u000144=150.50\u000110=123\u0001";
        return msg.getBytes();
    }
    
    @Benchmark
    public int parseLogonMessage() {
        parseBuffer.putBytes(0, logonMessage);
        return parser.parse(parseBuffer, 0, logonMessage.length);
    }
    
    @Benchmark
    public int parseHeartbeatMessage() {
        parseBuffer.putBytes(0, heartbeatMessage);
        return parser.parse(parseBuffer, 0, heartbeatMessage.length);
    }
    
    @Benchmark
    public int parseNewOrderMessage() {
        parseBuffer.putBytes(0, newOrderMessage);
        return parser.parse(parseBuffer, 0, newOrderMessage.length);
    }
    
    @Benchmark
    public int buildLogonMessage() {
        return builder.buildLogon(
                buildBuffer, 0,
                "CHRONOS", "CLIENT", currentSeqNum, 30);
    }
    
    @Benchmark
    public int buildHeartbeatMessage() {
        return builder.buildHeartbeat(
                buildBuffer, 0,
                "CHRONOS", "CLIENT", currentSeqNum, null);
    }
    
    @Benchmark
    public int buildLogoutMessage() {
        return builder.buildLogout(
                buildBuffer, 0,
                "CHRONOS", "CLIENT", currentSeqNum, "Normal shutdown");
    }
    
    @Benchmark
    public int buildTestRequestMessage() {
        return builder.buildTestRequest(
                buildBuffer, 0,
                "CHRONOS", "CLIENT", currentSeqNum, "TEST123");
    }
}
