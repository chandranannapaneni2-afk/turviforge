package io.forge.reportforge.core;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adaptive mapping from JTL CSV header names to field indices (FR-101).
 * Unknown columns are preserved as extras; missing optional columns yield -1
 * and downstream features degrade gracefully.
 */
public final class ColumnMap {

    /** Default JMeter save-service order, used for headerless files. */
    public static final String[] DEFAULT_ORDER = {
            "timeStamp", "elapsed", "label", "responseCode", "responseMessage",
            "threadName", "dataType", "success", "failureMessage", "bytes",
            "sentBytes", "grpThreads", "allThreads", "URL", "Latency",
            "IdleTime", "Connect"
    };

    public final int timeStamp, elapsed, label, responseCode, responseMessage,
            threadName, success, failureMessage, bytes, sentBytes,
            grpThreads, allThreads, latency, connect, hostname;
    public final int width;
    private final Map<String, Integer> all = new HashMap<>();

    public ColumnMap(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            all.put(norm(header.get(i)), i);
        }
        this.width = header.size();
        this.timeStamp = idx("timestamp");
        this.elapsed = idx("elapsed");
        this.label = idx("label");
        this.responseCode = idx("responsecode");
        this.responseMessage = idx("responsemessage");
        this.threadName = idx("threadname");
        this.success = idx("success");
        this.failureMessage = idx("failuremessage");
        this.bytes = idx("bytes");
        this.sentBytes = idx("sentbytes");
        this.grpThreads = idx("grpthreads");
        this.allThreads = idx("allthreads");
        this.latency = idx("latency");
        this.connect = idx("connect");
        this.hostname = idx("hostname");
    }

    /** A header row is recognised if it contains at least these three names. */
    public static boolean looksLikeHeader(List<String> row) {
        int hits = 0;
        for (String c : row) {
            String n = norm(c);
            if (n.equals("timestamp") || n.equals("elapsed") || n.equals("label")
                    || n.equals("responsecode") || n.equals("success")) hits++;
        }
        return hits >= 3;
    }

    public static ColumnMap defaults() {
        return new ColumnMap(List.of(DEFAULT_ORDER));
    }

    public boolean valid() {
        return timeStamp >= 0 && elapsed >= 0 && label >= 0;
    }

    private int idx(String normName) {
        Integer i = all.get(normName);
        return i == null ? -1 : i;
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
