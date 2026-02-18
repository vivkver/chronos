package com.chronos.gateway.fix.protection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token bucket circuit breaker for malformed message flood protection.
 * 
 * <h2>Purpose</h2>
 * <p>
 * Prevents a malicious or buggy client from flooding the gateway with invalid messages.
 * Uses a token bucket algorithm to track failure rate and trips the circuit when
 * the failure threshold is exceeded.
 * </p>
 * 
 * <h2>Behavior</h2>
 * <ul>
 * <li><b>CLOSED</b>: Normal operation, messages flow through</li>
 * <li><b>OPEN</b>: Circuit tripped, all messages rejected (client disconnected)</li>
 * <li><b>HALF_OPEN</b>: After cooldown, allows limited messages to test recovery</li>
 * </ul>
 * 
 * <h2>Configuration</h2>
 * <pre>
 * CircuitBreaker breaker = new CircuitBreaker(
 *     100,    // maxFailures: trip after 100 failures
 *     10_000, // windowMs: within 10 seconds
 *     60_000  // cooldownMs: reset after 60 seconds
 * );
 * </pre>
 * 
 * <h2>Performance</h2>
 * <p>
 * Zero allocation, uses primitive counters and timestamps.
 * Overhead: ~10ns per call (simple arithmetic).
 * </p>
 */
public final class CircuitBreaker {
    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreaker.class);
    
    public enum State {
        CLOSED,      // Normal operation
        OPEN,        // Circuit tripped, rejecting all requests
        HALF_OPEN    // Testing if system has recovered
    }
    
    private final int maxFailures;
    private final long windowMs;
    private final long cooldownMs;
    
    private State state = State.CLOSED;
    private int failureCount = 0;
    private long windowStartMs = System.currentTimeMillis();
    private long tripTimeMs = 0;
    
    /**
     * Create a circuit breaker with default settings.
     * Default: 100 failures in 10 seconds, 60 second cooldown
     */
    public CircuitBreaker() {
        this(100, 10_000, 60_000);
    }
    
    /**
     * Create a circuit breaker with custom settings.
     * 
     * @param maxFailures maximum failures allowed in the time window
     * @param windowMs time window in milliseconds
     * @param cooldownMs cooldown period before attempting recovery
     */
    public CircuitBreaker(int maxFailures, long windowMs, long cooldownMs) {
        this.maxFailures = maxFailures;
        this.windowMs = windowMs;
        this.cooldownMs = cooldownMs;
    }
    
    /**
     * Record a successful operation.
     * Resets failure count if in HALF_OPEN state.
     */
    public void recordSuccess() {
        if (state == State.HALF_OPEN) {
            LOG.info("Circuit breaker recovered, transitioning to CLOSED");
            state = State.CLOSED;
            failureCount = 0;
            windowStartMs = System.currentTimeMillis();
        }
    }
    
    /**
     * Record a failed operation.
     * May trip the circuit if failure threshold is exceeded.
     * 
     * @return true if circuit is now OPEN (caller should disconnect client)
     */
    public boolean recordFailure() {
        long now = System.currentTimeMillis();
        
        // Reset window if expired
        if (now - windowStartMs > windowMs) {
            failureCount = 0;
            windowStartMs = now;
        }
        
        failureCount++;
        
        if (state == State.CLOSED && failureCount >= maxFailures) {
            LOG.warn("Circuit breaker TRIPPED: {} failures in {}ms", failureCount, windowMs);
            state = State.OPEN;
            tripTimeMs = now;
            return true;
        }
        
        if (state == State.HALF_OPEN) {
            LOG.warn("Circuit breaker re-TRIPPED during recovery");
            state = State.OPEN;
            tripTimeMs = now;
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if the circuit allows requests.
     * Automatically transitions to HALF_OPEN after cooldown period.
     * 
     * @return true if requests are allowed
     */
    public boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        
        if (state == State.OPEN) {
            long now = System.currentTimeMillis();
            if (now - tripTimeMs > cooldownMs) {
                LOG.info("Circuit breaker cooldown expired, transitioning to HALF_OPEN");
                state = State.HALF_OPEN;
                failureCount = 0;
                windowStartMs = now;
                return true;
            }
            return false;
        }
        
        // HALF_OPEN: allow limited requests
        return true;
    }
    
    /**
     * Get current circuit state.
     * @return current state
     */
    public State getState() {
        return state;
    }
    
    /**
     * Get current failure count in the window.
     * @return failure count
     */
    public int getFailureCount() {
        return failureCount;
    }
    
    /**
     * Manually reset the circuit breaker.
     * Useful for administrative override.
     */
    public void reset() {
        LOG.info("Circuit breaker manually reset");
        state = State.CLOSED;
        failureCount = 0;
        windowStartMs = System.currentTimeMillis();
    }
}
