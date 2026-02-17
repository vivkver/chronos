package com.chronos.bench;

/**
 * Utility to calculate and verify FIX message checksums and body lengths.
 * Run this to verify the FIX message in FixBenchmark is valid.
 */
public class FixMessageValidator {
    
    private static final char SOH = '\u0001';
    
    public static void main(String[] args) {
        // The FIX message from FixBenchmark (MUST match FixBenchmark.java line 42)
        String fixMsg = "8=FIX.4.4\u00019=102\u000135=D\u000149=CLIENT1\u000156=CHRONOS\u000134=1\u000152=20231023-12:00:00.000\u000111=ORD001\u000155=AAPL\u000154=1\u000138=100\u000144=150.50\u000140=2\u000110=144\u0001";

        
        System.out.println("FIX Message Validator");
        System.out.println("=====================\n");
        
        // Display the message with visible delimiters
        System.out.println("Message (with | showing SOH):");
        System.out.println(fixMsg.replace(SOH, '|'));
        System.out.println();
        
        // Calculate body length (from after 9=XXX\u0001 to before 10=XXX\u0001)
        int bodyLengthStart = fixMsg.indexOf("35=");
        int checksumStart = fixMsg.indexOf("10=");
        int actualBodyLength = checksumStart - bodyLengthStart;
        
        // Extract declared body length
        int bodyLengthTagStart = fixMsg.indexOf("9=") + 2;
        int bodyLengthTagEnd = fixMsg.indexOf(SOH, bodyLengthTagStart);
        String declaredBodyLengthStr = fixMsg.substring(bodyLengthTagStart, bodyLengthTagEnd);
        int declaredBodyLength = Integer.parseInt(declaredBodyLengthStr);
        
        System.out.println("Body Length:");
        System.out.println("  Declared: " + declaredBodyLength);
        System.out.println("  Actual:   " + actualBodyLength);
        System.out.println("  Valid:    " + (declaredBodyLength == actualBodyLength ? "✓ YES" : "✗ NO"));
        System.out.println();
        
        // Calculate checksum (sum of all bytes before 10=XXX\u0001, modulo 256)
        byte[] msgBytes = fixMsg.substring(0, checksumStart).getBytes();
        int checksumValue = 0;
        for (byte b : msgBytes) {
            checksumValue += b & 0xFF;
        }
        checksumValue = checksumValue % 256;
        
        // Extract declared checksum
        int checksumTagStart = fixMsg.indexOf("10=") + 3;
        int checksumTagEnd = fixMsg.indexOf(SOH, checksumTagStart);
        String declaredChecksumStr = fixMsg.substring(checksumTagStart, checksumTagEnd);
        int declaredChecksum = Integer.parseInt(declaredChecksumStr);
        
        System.out.println("Checksum:");
        System.out.println("  Declared: " + String.format("%03d", declaredChecksum));
        System.out.println("  Actual:   " + String.format("%03d", checksumValue));
        System.out.println("  Valid:    " + (declaredChecksum == checksumValue ? "✓ YES" : "✗ NO"));
        System.out.println();
        
        // Parse and display all fields
        System.out.println("All Fields:");
        String[] fields = fixMsg.split(String.valueOf(SOH));
        for (String field : fields) {
            if (!field.isEmpty()) {
                String[] parts = field.split("=", 2);
                if (parts.length == 2) {
                    String tagName = getTagName(parts[0]);
                    System.out.println("  " + parts[0] + " (" + tagName + ") = " + parts[1]);
                }
            }
        }
        
        // Overall validation
        System.out.println();
        boolean isValid = (declaredBodyLength == actualBodyLength) && (declaredChecksum == checksumValue);
        System.out.println("Overall: " + (isValid ? "✓ VALID FIX MESSAGE" : "✗ INVALID FIX MESSAGE"));
    }
    
    private static String getTagName(String tag) {
        return switch (tag) {
            case "8" -> "BeginString";
            case "9" -> "BodyLength";
            case "35" -> "MsgType";
            case "49" -> "SenderCompID";
            case "56" -> "TargetCompID";
            case "34" -> "MsgSeqNum";
            case "52" -> "SendingTime";
            case "11" -> "ClOrdID";
            case "55" -> "Symbol";
            case "54" -> "Side";
            case "38" -> "OrderQty";
            case "44" -> "Price";
            case "40" -> "OrdType";
            case "10" -> "CheckSum";
            default -> "Unknown";
        };
    }
}
