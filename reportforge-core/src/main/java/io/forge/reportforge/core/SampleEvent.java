package io.forge.reportforge.core;

/** One parsed JTL row. Immutable; the unit of exchange between M1 and M2. */
public record SampleEvent(
        long timestampMs,
        int elapsedMs,
        String label,
        String responseCode,
        boolean success,
        String failureMessage,
        String responseMessage,
        int latencyMs,      // -1 if column absent
        int connectMs,      // -1 if column absent
        long bytes,
        long sentBytes,
        int grpThreads,
        int allThreads,
        String threadName,
        String hostname,
        boolean isTransaction,  // true if Transaction Controller sample (FR-107)
        boolean isSubSample,    // true if nested child sample
        String parentLabel) {   // parent label for sub-samples, null otherwise

    /** Convenience factory for CSV-parsed events (no transaction info). */
    public static SampleEvent csv(long timestampMs, int elapsedMs, String label,
                                  String responseCode, boolean success,
                                  String failureMessage, String responseMessage,
                                  int latencyMs, int connectMs,
                                  long bytes, long sentBytes,
                                  int grpThreads, int allThreads,
                                  String threadName, String hostname) {
        return new SampleEvent(timestampMs, elapsedMs, label, responseCode, success,
                failureMessage, responseMessage, latencyMs, connectMs,
                bytes, sentBytes, grpThreads, allThreads, threadName, hostname,
                false, false, null);
    }
}
