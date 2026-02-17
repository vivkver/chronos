package com.chronos.gateway.integration;

import com.chronos.gateway.fix.FixParser;
import com.chronos.gateway.fix.FixToSbeEncoder;
import com.chronos.schema.sbe.MessageHeaderDecoder;
import com.chronos.schema.sbe.NewOrderSingleDecoder;
import com.chronos.core.domain.OrderType;
import com.chronos.core.domain.Side;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the contract between the Gateway (Encoder) and the Sequencer (Decoder).
 */
public class IntegrationContractTest {

    @Test
    public void shouldEncodeAndDecodeNewOrderSingle() {
        // 1. Prepare raw FIX message (Tag 35=D, NewOrderSingle)
        // 8=FIX.4.4|9=...|35=D|11=ORD123|55=MSFT|54=1|38=100|44=150.50|40=2|...
        String rawFix = "8=FIX.4.4\u00019=100\u000135=D\u000134=1\u000149=CLIENT\u000156=CHRONOS\u0001" +
                        "11=12345\u0001" +          // ClOrdID (long)
                        "55=MSFT\u0001" +           // Symbol
                        "54=1\u0001" +              // Side = Buy
                        "38=100\u0001" +            // Qty = 100
                        "44=150.50\u0001" +         // Price = 150.50
                        "40=2\u0001" +              // OrdType = Limit
                        "10=000\u0001";

        // 2. Parse it
        UnsafeBuffer fixBuffer = new UnsafeBuffer(rawFix.getBytes(StandardCharsets.US_ASCII));
        FixParser parser = new FixParser();
        parser.parse(fixBuffer, 0, fixBuffer.capacity());

        // 3. Encode to SBE (Gateway Logic)
        UnsafeBuffer sbeBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));
        FixToSbeEncoder encoder = new FixToSbeEncoder();
        int encodedLength = encoder.encodeNewOrderSingle(parser, sbeBuffer, 0, 1);

        // 4. Decode with Sequencer Logic
        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        headerDecoder.wrap(sbeBuffer, 0);

        assertEquals(NewOrderSingleDecoder.TEMPLATE_ID, headerDecoder.templateId());

        NewOrderSingleDecoder orderDecoder = new NewOrderSingleDecoder();
        orderDecoder.wrap(sbeBuffer, MessageHeaderDecoder.ENCODED_LENGTH);

        // 5. Verify Fields
        assertEquals(12345L, orderDecoder.orderId()); // Mapped from ClOrdID
        assertEquals(15050000000L, orderDecoder.price()); // 150.50 * 10^8
        assertEquals(100, orderDecoder.quantity());
        assertEquals(Side.BUY, orderDecoder.side());
        assertEquals(OrderType.LIMIT, orderDecoder.orderType());
    }
}
