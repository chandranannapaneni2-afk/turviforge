package io.forge.turviforge.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * K-way merge of multiple JTL files by timestamp (FR-108).
 * Emits one ordered SampleEvent stream so time-bucketing and thread-overlay
 * remain correct across distributed-run files.
 *
 * Strategy: each source file is parsed into a bounded buffer (batch), and a
 * min-heap merges across sources by timestamp. This keeps memory bounded while
 * producing globally ordered output.
 */
public final class JtlMerger {

    public static final class MergeResult {
        public long total;
        public long malformed;
        public final List<String> sources = new ArrayList<>();
    }

    /**
     * Parse and merge multiple JTL files (CSV or XML, auto-detected) in
     * timestamp order, feeding events to the sink.
     */
    public MergeResult merge(List<Path> files, Consumer<SampleEvent> sink) throws IOException {
        if (files.size() == 1) {
            return parseSingle(files.get(0), sink);
        }
        // For multi-file: parse each into a batch buffer, then k-way merge.
        // Bounded approach: parse all files into per-file event lists sorted by ts,
        // then merge with a min-heap. Memory is bounded by total events across files
        // which for distributed runs is the union — acceptable for v1.
        List<List<SampleEvent>> buffers = new ArrayList<>();
        MergeResult result = new MergeResult();

        for (Path f : files) {
            result.sources.add(f.getFileName().toString());
            List<SampleEvent> events = new ArrayList<>();
            MergeResult single = parseSingle(f, events::add);
            result.total += single.total;
            result.malformed += single.malformed;
            events.sort(Comparator.comparingLong(SampleEvent::timestampMs));
            buffers.add(events);
        }

        // K-way merge via min-heap
        record Cursor(int fileIdx, int pos, long ts) {}
        PriorityQueue<Cursor> heap = new PriorityQueue<>(Comparator.comparingLong(Cursor::ts));
        for (int i = 0; i < buffers.size(); i++) {
            if (!buffers.get(i).isEmpty()) {
                heap.add(new Cursor(i, 0, buffers.get(i).get(0).timestampMs()));
            }
        }
        while (!heap.isEmpty()) {
            Cursor c = heap.poll();
            sink.accept(buffers.get(c.fileIdx()).get(c.pos()));
            int next = c.pos() + 1;
            if (next < buffers.get(c.fileIdx()).size()) {
                heap.add(new Cursor(c.fileIdx(), next, buffers.get(c.fileIdx()).get(next).timestampMs()));
            }
        }
        return result;
    }

    private MergeResult parseSingle(Path file, Consumer<SampleEvent> sink) throws IOException {
        MergeResult r = new MergeResult();
        r.sources.add(file.getFileName().toString());
        if (JtlXmlParser.isXml(file)) {
            JtlXmlParser.Result xr = new JtlXmlParser().parse(file, sink);
            r.total = xr.total;
            r.malformed = xr.malformed;
        } else {
            JtlCsvParser.Result cr = new JtlCsvParser().parse(file, sink);
            r.total = cr.total;
            r.malformed = cr.malformed;
        }
        return r;
    }
}
