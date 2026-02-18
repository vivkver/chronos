package com.chronos.gateway.fix.validation;

import com.chronos.gateway.fix.FixParser;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FIX message validation layer.
 */
class FIXValidatorTest {

        private FIXValidator validator;
        private FixParser parser;
        private UnsafeBuffer buffer;

        @BeforeEach
        void setUp() {
                validator = new FIXValidator();
                parser = new FixParser();
                buffer = new UnsafeBuffer(ByteBuffer.allocate(4096));
        }

        @Test
        void testValidNewOrderSingle() {
                // Valid NewOrderSingle message
                String fixMessage = "8=FIX.4.4\u00019=150\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                                +
                                "11=ORD001\u000155=AAPL\u000154=1\u000138=100\u000140=2\u000144=150.50\u000110=000\u0001";

                byte[] bytes = fixMessage.getBytes();
                buffer.putBytes(0, bytes);

                parser.parse(buffer, 0, bytes.length);
                ValidationResult result = validator.validate(parser);

                assertTrue(result.isValid(), "Valid message should pass validation");
                assertTrue(result.isValid(), "Valid message should pass validation");
        }

        @Test
        void testMissingRequiredField() {
                // Missing Symbol (tag 55)
                String fixMessage = "8=FIX.4.4\u00019=120\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                                +
                                "11=ORD001\u000154=1\u000138=100\u000140=2\u000144=150.50\u000110=000\u0001";

                byte[] bytes = fixMessage.getBytes();
                buffer.putBytes(0, bytes);

                parser.parse(buffer, 0, bytes.length);
                ValidationResult result = validator.validate(parser);

                assertFalse(result.isValid());
                assertEquals(ValidationResult.REJECT_REASON_REQUIRED_TAG_MISSING, result.getSessionRejectReason());
        }

        @Test
        void testInvalidSide() {
                // Invalid Side value (54=9, should be 1 or 2)
                String fixMessage = "8=FIX.4.4\u00019=150\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                                +
                                "11=ORD001\u000155=AAPL\u000154=9\u000138=100\u000140=2\u000144=150.50\u000110=000\u0001";

                byte[] bytes = fixMessage.getBytes();
                buffer.putBytes(0, bytes);

                parser.parse(buffer, 0, bytes.length);
                ValidationResult result = validator.validate(parser);

                assertFalse(result.isValid());
                assertEquals(ValidationResult.REJECT_REASON_VALUE_IS_INCORRECT, result.getSessionRejectReason());
        }

        @Test
        void testPriceOutOfRange() {
                // Price exceeds max limit (using custom validator with low limit)
                FIXValidator strictValidator = new FIXValidator(
                                100_000_000L, // Max price: $1.00 (scaled by 10^8)
                                1_000_000,
                                false,
                                null);

                // Price = 150.50 (exceeds $1.00 limit)
                String fixMessage = "8=FIX.4.4\u00019=150\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                                +
                                "11=ORD001\u000155=AAPL\u000154=1\u000138=100\u000140=2\u000144=150.50\u000110=000\u0001";

                byte[] bytes = fixMessage.getBytes();
                buffer.putBytes(0, bytes);

                parser.parse(buffer, 0, bytes.length);
                ValidationResult result = strictValidator.validate(parser);

                assertFalse(result.isValid());
                assertEquals(ValidationResult.REJECT_REASON_VALUE_IS_INCORRECT, result.getSessionRejectReason());
        }

        @Test
        void testQuantityOutOfRange() {
                // Quantity exceeds max limit
                FIXValidator strictValidator = new FIXValidator(
                                100_000_000L * 100_000_000L,
                                100, // Max quantity: 100
                                false,
                                null);

                // Quantity = 1000 (exceeds 100 limit)
                String fixMessage = "8=FIX.4.4\u00019=150\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                                +
                                "11=ORD001\u000155=AAPL\u000154=1\u000138=1000\u000140=2\u000144=150.50\u000110=000\u0001";

                byte[] bytes = fixMessage.getBytes();
                buffer.putBytes(0, bytes);

                parser.parse(buffer, 0, bytes.length);
                ValidationResult result = strictValidator.validate(parser);

                assertFalse(result.isValid());
                assertEquals(ValidationResult.REJECT_REASON_VALUE_IS_INCORRECT, result.getSessionRejectReason());
        }

        @Test
        void testSymbolWhitelist() {
                // Symbol not in whitelist
                FIXValidator whitelistValidator = new FIXValidator(
                                100_000_000L * 100_000_000L,
                                1_000_000,
                                true, // Enable whitelist
                                new String[] { "MSFT", "GOOGL" } // AAPL not allowed
                );

                String fixMessage = "8=FIX.4.4\u00019=150\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                                +
                                "11=ORD001\u000155=AAPL\u000154=1\u000138=100\u000140=2\u000144=150.50\u000110=000\u0001";

                byte[] bytes = fixMessage.getBytes();
                buffer.putBytes(0, bytes);

                parser.parse(buffer, 0, bytes.length);
                ValidationResult result = whitelistValidator.validate(parser);

                assertFalse(result.isValid());
                assertEquals(ValidationResult.REJECT_REASON_VALUE_IS_INCORRECT, result.getSessionRejectReason());
        }

        @Test
        void testSymbolWhitelistAllowed() {
                // Symbol in whitelist
                FIXValidator whitelistValidator = new FIXValidator(
                                100_000_000L * 100_000_000L,
                                1_000_000,
                                true,
                                new String[] { "AAPL", "MSFT", "GOOGL" });

                String fixMessage = "8=FIX.4.4\u00019=150\u000135=D\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                                +
                                "11=ORD001\u000155=AAPL\u000154=1\u000138=100\u000140=2\u000144=150.50\u000110=000\u0001";

                byte[] bytes = fixMessage.getBytes();
                buffer.putBytes(0, bytes);

                parser.parse(buffer, 0, bytes.length);
                ValidationResult result = whitelistValidator.validate(parser);

                assertTrue(result.isValid());
        }

        @Test
        void testValidOrderCancelRequest() {
                // Valid OrderCancelRequest
                String fixMessage = "8=FIX.4.4\u00019=120\u000135=F\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                                +
                                "11=ORD002\u000141=ORD001\u000155=AAPL\u000154=1\u000110=000\u0001";

                byte[] bytes = fixMessage.getBytes();
                buffer.putBytes(0, bytes);

                parser.parse(buffer, 0, bytes.length);
                ValidationResult result = validator.validate(parser);

                assertTrue(result.isValid());
        }

        @Test
        void testSessionLevelMessagesValid() {
                // Logon message should pass validation
                String fixMessage = "8=FIX.4.4\u00019=80\u000135=A\u000149=CLIENT\u000156=CHRONOS\u000134=1\u000152=20240101-12:00:00\u0001"
                                +
                                "98=0\u0001108=30\u000110=000\u0001";

                byte[] bytes = fixMessage.getBytes();
                buffer.putBytes(0, bytes);

                parser.parse(buffer, 0, bytes.length);
                ValidationResult result = validator.validate(parser);

                assertTrue(result.isValid(), "Session-level messages should pass validation");
        }
}
