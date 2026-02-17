package com.chronos.bench;

/**
 * Utility to generate a valid FIX message with correct body length and checksum.
 */
public class FixMessageGenerator {
    
    private static final char SOH = '\u0001';
    
    public static void main(String[] args) {
        // Build the message without body length and checksum first
        StringBuilder msg = new StringBuilder();
        msg.append("8=FIX.4.4").append(SOH);
        msg.append("9=PLACEHOLDER").append(SOH);  // Will calculate
        
        // Body starts here
        int bodyStart = msg.length();
        msg.append("35=D").append(SOH);
        msg.append("49=CLIENT1").append(SOH);
        msg.append("56=CHRONOS").append(SOH);
        msg.append("34=1").append(SOH);
        msg.append("52=20231023-12:00:00.000").append(SOH);
        msg.append("11=ORD001").append(SOH);
        msg.append("55=AAPL").append(SOH);
        msg.append("54=1").append(SOH);
        msg.append("38=100").append(SOH);
        msg.append("44=150.50").append(SOH);
        msg.append("40=2").append(SOH);
        // Body ends here (before checksum)
        
        // Calculate body length
        int bodyLength = msg.length() - bodyStart;
        String bodyLengthStr = String.valueOf(bodyLength);
        
        // Replace placeholder with actual body length
        String msgWithBodyLength = msg.toString().replace("9=PLACEHOLDER", "9=" + bodyLengthStr);
        
        // Calculate checksum (sum of all bytes before checksum field, modulo 256)
        byte[] msgBytes = msgWithBodyLength.getBytes();
        int checksumValue = 0;
        for (byte b : msgBytes) {
            checksumValue += b & 0xFF;
        }
        checksumValue = checksumValue % 256;
        String checksumStr = String.format("%03d", checksumValue);
        
        // Add checksum
        String finalMsg = msgWithBodyLength + "10=" + checksumStr + SOH;
        
        // Display results
        System.out.println("Generated Valid FIX Message");
        System.out.println("===========================\n");
        
        System.out.println("Message (with | showing SOH):");
        System.out.println(finalMsg.replace(SOH, '|'));
        System.out.println();
        
        System.out.println("Java String Literal:");
        System.out.println("\"" + finalMsg.replace(String.valueOf(SOH), "\\u0001") + "\"");
        System.out.println();
        
        System.out.println("Body Length: " + bodyLength);
        System.out.println("Checksum: " + checksumStr);
        System.out.println();
        
        // Verify
        System.out.println("Verification:");
        System.out.println("  Message length: " + finalMsg.length() + " bytes");
        System.out.println("  Body starts at position: " + bodyStart);
        System.out.println("  Body length: " + bodyLength + " bytes");
    }
}
