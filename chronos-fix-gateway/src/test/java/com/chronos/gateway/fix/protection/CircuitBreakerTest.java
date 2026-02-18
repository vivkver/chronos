package com.chronos.gateway.fix.protection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CircuitBreaker.
 */
class CircuitBreakerTest {
    
    private CircuitBreaker breaker;
    
    @BeforeEach
    void setUp() {
        // 5 failures in 1 second window, 2 second cooldown
        breaker = new CircuitBreaker(5, 1000, 2000);
    }
    
    @Test
    void testInitialStateClosed() {
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(breaker.allowRequest());
    }
    
    @Test
    void testCircuitTripsAfterMaxFailures() {
        // Record 4 failures - should stay closed
        for (int i = 0; i < 4; i++) {
            assertFalse(breaker.recordFailure(), "Should not trip before max failures");
            assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        }
        
        // 5th failure should trip the circuit
        assertTrue(breaker.recordFailure(), "Should trip on 5th failure");
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.allowRequest(), "Should reject requests when OPEN");
    }
    
    @Test
    void testCircuitResetsAfterCooldown() throws InterruptedException {
        // Trip the circuit
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure();
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        
        // Wait for cooldown (2 seconds)
        Thread.sleep(2100);
        
        // Should transition to HALF_OPEN
        assertTrue(breaker.allowRequest(), "Should allow requests after cooldown");
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());
    }
    
    @Test
    void testSuccessInHalfOpenTransitionsToClosed() throws InterruptedException {
        // Trip the circuit
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure();
        }
        
        // Wait for cooldown
        Thread.sleep(2100);
        breaker.allowRequest(); // Transition to HALF_OPEN
        
        // Record success
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(0, breaker.getFailureCount());
    }
    
    @Test
    void testFailureInHalfOpenRetrips() throws InterruptedException {
        // Trip the circuit
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure();
        }
        
        // Wait for cooldown
        Thread.sleep(2100);
        breaker.allowRequest(); // Transition to HALF_OPEN
        
        // Record failure - should re-trip
        assertTrue(breaker.recordFailure(), "Should re-trip on failure in HALF_OPEN");
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }
    
    @Test
    void testWindowReset() throws InterruptedException {
        // Record 3 failures
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertEquals(3, breaker.getFailureCount());
        
        // Wait for window to expire (1 second)
        Thread.sleep(1100);
        
        // Record 2 more failures - should not trip (window reset)
        assertFalse(breaker.recordFailure());
        assertFalse(breaker.recordFailure());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }
    
    @Test
    void testManualReset() {
        // Trip the circuit
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure();
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        
        // Manual reset
        breaker.reset();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(0, breaker.getFailureCount());
        assertTrue(breaker.allowRequest());
    }
}
