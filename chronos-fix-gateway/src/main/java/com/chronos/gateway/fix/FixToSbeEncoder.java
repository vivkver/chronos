package com.chronos.gateway.fix;

import com.chronos.core.domain.OrderType;
import com.chronos.core.domain.Side;
import com.chronos.schema.sbe.CancelOrderEncoder;
import com.chronos.schema.sbe.MessageHeaderEncoder;
import com.chronos.schema.sbe.NewOrderSingleEncoder;
import com.chronos.schema.sbe.QuoteRequestEncoder;
import com.chronos.schema.sbe.QuoteEncoder;
import org.agrona.MutableDirectBuffer;

/**
 * Translates parsed FIX fields directly into SBE-encoded messages.
 *
 * <h2>Zero-Copy Design</h2>
 * <p>
 * Writes directly into the Aeron publication buffer — no intermediate objects
 * are created. The FIX parser provides field values via direct buffer reads,
 * and
 * this encoder writes them as SBE into the target buffer.
 * </p>
 *
 * <h2>FIX → SBE Field Mapping</h2>
 * 
 * <pre>
 *   FIX Tag 11  (ClOrdID)   → orderId  (hashed or parsed as long)
 *   FIX Tag 44  (Price)     → price    (fixed-point * 10^8)
 *   FIX Tag 49  (SenderID)  → clientId (hashed)
 *   FIX Tag 38  (OrderQty)  → quantity
 *   FIX Tag 54  (Side)      → side     (FIX '1'=Buy→0, '2'=Sell→1)
 *   FIX Tag 40  (OrdType)   → orderType (FIX '1'=Market→1, '2'=Limit→0)
 * </pre>
 */
public final class FixToSbeEncoder {

    // Pre-allocated flyweight encoders (reused per call)
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final NewOrderSingleEncoder orderEncoder = new NewOrderSingleEncoder();
    private final CancelOrderEncoder cancelEncoder = new CancelOrderEncoder();
    private final QuoteRequestEncoder quoteRequestEncoder = new QuoteRequestEncoder();
    private final QuoteEncoder quoteEncoder = new QuoteEncoder();

    private long orderIdSequence = 0;

    /**
     * Encode a parsed FIX message into SBE NewOrderSingle format.
     *
     * @param parser       the FIX parser with a message already parsed
     * @param targetBuffer buffer to write the SBE message into
     * @param targetOffset offset in the target buffer
     * @param instrumentId resolved instrument ID (from symbol lookup)
     * @return total bytes written (header + body)
     */
    public int encodeNewOrderSingle(final FixParser parser,
            final MutableDirectBuffer targetBuffer,
            final int targetOffset,
            final int instrumentId) {

        // Write SBE message header
        headerEncoder.wrap(targetBuffer, targetOffset)
                .blockLength(NewOrderSingleEncoder.BLOCK_LENGTH)
                .templateId(NewOrderSingleEncoder.TEMPLATE_ID)
                .schemaId(NewOrderSingleEncoder.SCHEMA_ID)
                .version(NewOrderSingleEncoder.SCHEMA_VERSION);

        // Write NewOrderSingle body
        final int bodyOffset = targetOffset + MessageHeaderEncoder.ENCODED_LENGTH;

        // Map FIX Side: '1'=Buy→Side.BUY, '2'=Sell→Side.SELL
        final byte fixSide = parser.getCharValue(FixParser.TAG_SIDE, (byte) '1');
        final byte sbeSide = (fixSide == '1') ? Side.BUY : Side.SELL;

        // Map FIX OrdType: '1'=Market→OrderType.MARKET, '2'=Limit→OrderType.LIMIT
        final byte fixOrdType = parser.getCharValue(FixParser.TAG_ORD_TYPE, (byte) '2');
        final byte sbeOrderType = (fixOrdType == '1') ? OrderType.MARKET : OrderType.LIMIT;

        // Extract order ID (ClOrdID parsed as long, or use sequence)
        final long orderId = parser.getLongValue(FixParser.TAG_CL_ORD_ID, ++orderIdSequence);

        // Extract price as fixed-point (e.g., "150.50" → 15050000000)
        final long price = parser.getFixedPointPrice(FixParser.TAG_PRICE, 0L);

        // Extract quantity
        final int quantity = parser.getIntValue(FixParser.TAG_ORDER_QTY, 0);

        // Client ID from SenderCompID (hashed or from parsed value)
        final long clientId = parser.getLongValue(FixParser.TAG_SENDER_COMP_ID, 0L);

        orderEncoder.wrap(targetBuffer, bodyOffset)
                .orderId(orderId)
                .price(price)
                .clientId(clientId)
                .timestampNs(0L) // Will be set by the cluster (deterministic timestamp)
                .instrumentId(instrumentId)
                .quantity(quantity)
                .side(sbeSide)
                .orderType(sbeOrderType);

        return MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH;
    }

    /**
     * Encode a parsed FIX message into SBE CancelOrder format.
     */
    public int encodeCancelOrder(final FixParser parser,
            final MutableDirectBuffer targetBuffer,
            final int targetOffset,
            final int instrumentId) {

        // Write SBE message header
        headerEncoder.wrap(targetBuffer, targetOffset)
                .blockLength(CancelOrderEncoder.BLOCK_LENGTH)
                .templateId(CancelOrderEncoder.TEMPLATE_ID)
                .schemaId(CancelOrderEncoder.SCHEMA_ID)
                .version(CancelOrderEncoder.SCHEMA_VERSION);

        // Write CancelOrder body
        final int bodyOffset = targetOffset + MessageHeaderEncoder.ENCODED_LENGTH;

        // Extract order ID (ClOrdID parsed as long)
        // Note: For cancels, ClOrdID usually refers to the NEW ID, and OrigClOrdID refers to the old ID.
        // For simplicity in this demo, we assume tag 11 (ClOrdID) is the target order ID to cancel.
        final long orderId = parser.getLongValue(FixParser.TAG_CL_ORD_ID, 0L);

        // Client ID from SenderCompID
        final long clientId = parser.getLongValue(FixParser.TAG_SENDER_COMP_ID, 0L);

        cancelEncoder.wrap(targetBuffer, bodyOffset)
                .orderId(orderId)
                .clientId(clientId)
                .instrumentId(instrumentId);

        return MessageHeaderEncoder.ENCODED_LENGTH + CancelOrderEncoder.BLOCK_LENGTH;
    }

    /**
     * Encode a parsed FIX QuoteRequest message into SBE format.
     *
     * @param parser       the FIX parser with a message already parsed
     * @param targetBuffer buffer to write the SBE message into
     * @param targetOffset offset in the target buffer
     * @param instrumentId resolved instrument ID (from symbol lookup)
     * @return total bytes written (header + body)
     */
    public int encodeQuoteRequest(final FixParser parser,
            final MutableDirectBuffer targetBuffer,
            final int targetOffset,
            final int instrumentId) {

        // Write SBE message header
        headerEncoder.wrap(targetBuffer, targetOffset)
                .blockLength(QuoteRequestEncoder.BLOCK_LENGTH)
                .templateId(QuoteRequestEncoder.TEMPLATE_ID)
                .schemaId(QuoteRequestEncoder.SCHEMA_ID)
                .version(QuoteRequestEncoder.SCHEMA_VERSION);

        // Write QuoteRequest body
        final int bodyOffset = targetOffset + MessageHeaderEncoder.ENCODED_LENGTH;

        // Extract FIX fields
        final long quoteReqID = parser.getLongValue(FixParser.TAG_QUOTE_REQ_ID, 0L);
        final long clientId = parser.getLongValue(FixParser.TAG_SENDER_COMP_ID, 0L);
        final int quantity = parser.getIntValue(FixParser.TAG_ORDER_QTY, 0);
        
        // Map FIX Side: '1'=Buy→Side.BUY, '2'=Sell→Side.SELL
        final byte fixSide = parser.getCharValue(FixParser.TAG_SIDE, (byte) '1');
        final byte sbeSide = (fixSide == '1') ? Side.BUY : Side.SELL;

        quoteRequestEncoder.wrap(targetBuffer, bodyOffset)
                .quoteReqID(quoteReqID)
                .clientId(clientId)
                .instrumentId(instrumentId)
                .quantity(quantity)
                .side(sbeSide)
                .timestampNs(0L); // Will be set by the cluster

        return MessageHeaderEncoder.ENCODED_LENGTH + QuoteRequestEncoder.BLOCK_LENGTH;
    }

    /**
     * Encode a parsed FIX Quote message into SBE format.
     *
     * @param parser       the FIX parser with a message already parsed
     * @param targetBuffer buffer to write the SBE message into
     * @param targetOffset offset in the target buffer
     * @param instrumentId resolved instrument ID (from symbol lookup)
     * @return total bytes written (header + body)
     */
    public int encodeQuote(final FixParser parser,
            final MutableDirectBuffer targetBuffer,
            final int targetOffset,
            final int instrumentId) {

        // Write SBE message header
        headerEncoder.wrap(targetBuffer, targetOffset)
                .blockLength(QuoteEncoder.BLOCK_LENGTH)
                .templateId(QuoteEncoder.TEMPLATE_ID)
                .schemaId(QuoteEncoder.SCHEMA_ID)
                .version(QuoteEncoder.SCHEMA_VERSION);

        // Write Quote body
        final int bodyOffset = targetOffset + MessageHeaderEncoder.ENCODED_LENGTH;

        // Extract FIX fields
        final long quoteID = parser.getLongValue(FixParser.TAG_QUOTE_ID, 0L);
        final long quoteReqID = parser.getLongValue(FixParser.TAG_QUOTE_REQ_ID, 0L);
        final long clientId = parser.getLongValue(FixParser.TAG_SENDER_COMP_ID, 0L);
        
        // Extract bid/ask prices as fixed-point
        final long bidPrice = parser.getFixedPointPrice(FixParser.TAG_BID_PX, 0L);
        final int bidSize = parser.getIntValue(FixParser.TAG_BID_SIZE, 0);
        final long askPrice = parser.getFixedPointPrice(FixParser.TAG_OFFER_PX, 0L);
        final int askSize = parser.getIntValue(FixParser.TAG_OFFER_SIZE, 0);

        quoteEncoder.wrap(targetBuffer, bodyOffset)
                .quoteID(quoteID)
                .quoteReqID(quoteReqID)
                .clientId(clientId)
                .instrumentId(instrumentId)
                .bidPrice(bidPrice)
                .bidSize(bidSize)
                .askPrice(askPrice)
                .askSize(askSize)
                .timestampNs(0L); // Will be set by the cluster

        return MessageHeaderEncoder.ENCODED_LENGTH + QuoteEncoder.BLOCK_LENGTH;
    }

    /** Total encoding size for a NewOrderSingle with header. */
    public static int encodedSize() {
        return MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH;
    }
}
