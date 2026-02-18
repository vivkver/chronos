package com.chronos.matching;

import jdk.incubator.vector.LongVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating PriceScanner implementations with automatic SIMD feature detection.
 * 
 * <h2>Purpose</h2>
 * <p>
 * Isolates the incubating Vector API usage and provides graceful fallback to scalar
 * implementation when SIMD is unavailable or disabled. This protects against future
 * API changes in jdk.incubator.vector.
 * </p>
 * 
 * <h2>Selection Strategy</h2>
 * <ol>
 * <li>Check environment variable: {@code CHRONOS_DISABLE_SIMD=true} → force scalar</li>
 * <li>Check Vector API availability: try to load {@code LongVector.SPECIES_PREFERRED}</li>
 * <li>If available → {@link VectorizedPriceScanner}</li>
 * <li>If unavailable → {@link ScalarPriceScanner}</li>
 * </ol>
 * 
 * <h2>Usage</h2>
 * <pre>
 * // Automatic selection
 * PriceScanner scanner = PriceScannerFactory.create();
 * 
 * // Force scalar (for testing or compatibility)
 * System.setProperty("CHRONOS_DISABLE_SIMD", "true");
 * PriceScanner scanner = PriceScannerFactory.create();
 * </pre>
 * 
 * <h2>Migration Path</h2>
 * <p>
 * When Vector API finalizes (JEP 489), this factory can be updated to remove
 * the try-catch and directly use the vectorized implementation.
 * </p>
 */
public final class PriceScannerFactory {
    private static final Logger LOG = LoggerFactory.getLogger(PriceScannerFactory.class);
    
    private static final String DISABLE_SIMD_ENV = "CHRONOS_DISABLE_SIMD";
    private static final boolean SIMD_AVAILABLE;
    private static final String IMPLEMENTATION_TYPE;
    
    static {
        boolean simdAvailable = false;
        String implType = "SCALAR";
        
        // Check if SIMD is explicitly disabled
        String disableSIMD = System.getenv(DISABLE_SIMD_ENV);
        if (disableSIMD == null) {
            disableSIMD = System.getProperty(DISABLE_SIMD_ENV);
        }
        
        if ("true".equalsIgnoreCase(disableSIMD)) {
            LOG.info("SIMD explicitly disabled via {} environment variable/property", DISABLE_SIMD_ENV);
            simdAvailable = false;
            implType = "SCALAR (forced)";
        } else {
            // Try to detect Vector API availability
            try {
                // Attempt to access Vector API
                var species = LongVector.SPECIES_PREFERRED;
                int lanes = species.length();
                
                simdAvailable = true;
                implType = String.format("VECTORIZED (SIMD %d-lane)", lanes);
                LOG.info("Vector API detected: {} lanes available", lanes);
            } catch (UnsupportedOperationException e) {
                LOG.warn("Vector API not supported on this platform: {}", e.getMessage());
                simdAvailable = false;
                implType = "SCALAR (Vector API unsupported)";
            } catch (Exception e) {
                LOG.warn("Failed to initialize Vector API, falling back to scalar: {}", e.getMessage());
                simdAvailable = false;
                implType = "SCALAR (Vector API initialization failed)";
            }
        }
        
        SIMD_AVAILABLE = simdAvailable;
        IMPLEMENTATION_TYPE = implType;
        
        LOG.info("PriceScannerFactory initialized: {}", IMPLEMENTATION_TYPE);
    }
    
    /**
     * Create a PriceScanner instance using automatic feature detection.
     * 
     * @return VectorizedPriceScanner if SIMD available, otherwise ScalarPriceScanner
     */
    public static PriceScanner create() {
        if (SIMD_AVAILABLE) {
            return new VectorizedPriceScanner();
        } else {
            return new ScalarPriceScanner();
        }
    }
    
    /**
     * Force creation of a vectorized scanner (throws if SIMD unavailable).
     * Use only when SIMD is required.
     * 
     * @return VectorizedPriceScanner
     * @throws UnsupportedOperationException if SIMD is not available
     */
    public static PriceScanner createVectorized() {
        if (!SIMD_AVAILABLE) {
            throw new UnsupportedOperationException(
                "SIMD not available. Current implementation: " + IMPLEMENTATION_TYPE
            );
        }
        return new VectorizedPriceScanner();
    }
    
    /**
     * Force creation of a scalar scanner.
     * Useful for testing or compatibility mode.
     * 
     * @return ScalarPriceScanner
     */
    public static PriceScanner createScalar() {
        return new ScalarPriceScanner();
    }
    
    /**
     * Check if SIMD is available on this platform.
     * 
     * @return true if VectorizedPriceScanner can be used
     */
    public static boolean isSIMDAvailable() {
        return SIMD_AVAILABLE;
    }
    
    /**
     * Get a human-readable description of the current implementation.
     * 
     * @return implementation type string (e.g., "VECTORIZED (SIMD 8-lane)" or "SCALAR")
     */
    public static String getImplementationType() {
        return IMPLEMENTATION_TYPE;
    }
    
    // Private constructor to prevent instantiation
    private PriceScannerFactory() {
        throw new AssertionError("Utility class should not be instantiated");
    }
}
