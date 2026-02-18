package com.chronos.gateway.fix.validation;

/**
 * Zero-allocation validation result for FIX message validation.
 * 
 * <h2>Design</h2>
 * <p>
 * Uses static singleton instances to avoid object allocation on the hot path.
 * Each validation outcome is represented by a pre-allocated instance.
 * </p>
 * 
 * <h2>Usage</h2>
 * 
 * <pre>
 * ValidationResult result = validator.validate(buffer, offset, length);
 * if (result.isValid()) {
 *     // proceed with message processing
 * } else {
 *     // send FIX Reject (35=3) with result.getRejectionCode()
 * }
 * </pre>
 */
public final class ValidationResult {

    // Standard FIX SessionRejectReason (Tag 373) codes
    public static final int REJECT_REASON_INVALID_TAG_NUMBER = 0;
    public static final int REJECT_REASON_REQUIRED_TAG_MISSING = 1;
    public static final int REJECT_REASON_TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE = 2;
    public static final int REJECT_REASON_UNDEFINED_TAG = 3;
    public static final int REJECT_REASON_TAG_SPECIFIED_WITHOUT_A_VALUE = 4;
    public static final int REJECT_REASON_VALUE_IS_INCORRECT = 5;
    public static final int REJECT_REASON_INCORRECT_DATA_FORMAT_FOR_VALUE = 6;
    public static final int REJECT_REASON_DECRYPTION_PROBLEM = 7;
    public static final int REJECT_REASON_SIGNATURE_PROBLEM = 8;
    public static final int REJECT_REASON_COMPID_PROBLEM = 9;
    public static final int REJECT_REASON_SENDINGTIME_ACCURACY_PROBLEM = 10;
    public static final int REJECT_REASON_INVALID_MSGTYPE = 11;

    private boolean valid;
    private int sessionRejectReason; // Tag 373
    private int refTagId; // Tag 371
    private String text; // Tag 58

    public ValidationResult() {
        reset();
    }

    public void reset() {
        this.valid = true;
        this.sessionRejectReason = 0;
        this.refTagId = 0;
        this.text = null;
    }

    public void reject(int sessionRejectReason, int refTagId, String text) {
        this.valid = false;
        this.sessionRejectReason = sessionRejectReason;
        this.refTagId = refTagId;
        this.text = text;
    }

    public boolean isValid() {
        return valid;
    }

    public int getSessionRejectReason() {
        return sessionRejectReason;
    }

    public int getRefTagId() {
        return refTagId;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        if (valid)
            return "ValidationResult{VALID}";
        return "ValidationResult{INVALID, reason=" + sessionRejectReason + ", tag=" + refTagId + ", text='" + text
                + "'}";
    }
}
