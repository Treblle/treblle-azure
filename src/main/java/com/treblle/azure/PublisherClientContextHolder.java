package com.treblle.azure;

/**
 * Context holder for publisher client state management.
 */
public class PublisherClientContextHolder {
    
    /**
     * Thread-local storage for publish attempts counter.
     */
    public static final ThreadLocal<Integer> PUBLISH_ATTEMPTS = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 3; // Default retry attempts
        }
    };
    
    /**
     * Reset the publish attempts for the current thread.
     */
    public static void reset() {
        PUBLISH_ATTEMPTS.set(3);
    }
    
    /**
     * Clear the context for the current thread.
     */
    public static void clear() {
        PUBLISH_ATTEMPTS.remove();
    }
}
