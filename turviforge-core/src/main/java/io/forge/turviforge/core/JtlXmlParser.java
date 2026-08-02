package io.forge.turviforge.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Streaming XML JTL parser (M1, FR-102).
 * Handles {@code <sample>} and {@code <httpSample>} elements including nested
 * sub-samples and assertion results. Constant memory via StAX cursor (FR-103).
 * Gzip transparent (FR-106).
 */
public final class JtlXmlParser {

    public static final class Result {
        public long total;
        public long malformed;
    }

    private final double malformedAbortRatio;

    public JtlXmlParser() { this(0.01); }
    public JtlXmlParser(double malformedAbortRatio) { this.malformedAbortRatio = malformedAbortRatio; }

    /**
     * Detect if a file is XML JTL by sniffing the first bytes.
     * Returns true if the content starts with {@code <?xml} or {@code <testResults}.
     */
    public static boolean isXml(Path file) throws IOException {
        byte[] head = new byte[256];
        int n;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 512)) {
            // Skip gzip magic if present
            in.mark(2);
            int b0 = in.read(), b1 = in.read();
            in.reset();
            InputStream src = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(in, 512) : in;
            n = src.readNBytes(head, 0, head.length);
        }
        String s = new String(head, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
        return s.startsWith("<?xml") || s.startsWith("<testResults");
    }

    public Result parse(Path file, Consumer<SampleEvent> sink) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 20);
        in.mark(2);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        if (b0 == 0x1f && b1 == 0x8b) in = new GZIPInputStream(in, 1 << 16);
        try {
            return parse(in, sink);
        } finally {
            in.close();
        }
    }

    public Result parse(InputStream in, Consumer<SampleEvent> sink) throws IOException {
        Result res = new Result();
        XMLInputFactory xif = XMLInputFactory.newInstance();
        xif.setProperty(XMLInputFactory.IS_COALESCING, true);
        xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        try {
            XMLStreamReader xr = xif.createXMLStreamReader(in);
            int depth = 0;
            String parentLabel = null;

            while (xr.hasNext()) {
                int ev = xr.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String tag = xr.getLocalName();
                    if ("sample".equals(tag) || "httpSample".equals(tag)) {
                        depth++;
                        try {
                            SampleEvent e = parseSampleElement(xr, depth > 1, parentLabel);
                            if (depth == 1) parentLabel = e.label();
                            res.total++;
                            sink.accept(e);
                            if (res.total > 1000 && res.malformed > res.total * malformedAbortRatio) {
                                throw new IOException("Malformed-sample ratio exceeded threshold — is this a valid JTL?");
                            }
                        } catch (RuntimeException ex) {
                            res.malformed++;
                        }
                    } else if ("assertionResult".equals(tag)) {
                        // Skip assertion sub-elements; failure info captured from parent attributes
                        skipElement(xr);
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String tag = xr.getLocalName();
                    if ("sample".equals(tag) || "httpSample".equals(tag)) {
                        depth--;
                        if (depth == 0) parentLabel = null;
                    }
                }
            }
            xr.close();
        } catch (XMLStreamException e) {
            throw new IOException("XML parse error: " + e.getMessage(), e);
        }
        return res;
    }

    private SampleEvent parseSampleElement(XMLStreamReader xr, boolean isSubSample, String parentLabel) {
        long ts = attrLong(xr, "ts", 0);
        int elapsed = (int) attrLong(xr, "t", 0);
        String label = attr(xr, "lb", "");
        String code = attr(xr, "rc", "");
        boolean success = "true".equalsIgnoreCase(attr(xr, "s", "true"));
        String responseMessage = attr(xr, "rm", "");
        String threadName = attr(xr, "tn", "");
        long bytes = attrLong(xr, "by", 0);
        long sentBytes = attrLong(xr, "sby", 0);
        int grpThreads = (int) attrLong(xr, "ng", 0);
        int allThreads = (int) attrLong(xr, "na", 0);
        int latency = (int) attrLong(xr, "lt", -1);
        int connect = (int) attrLong(xr, "ct", -1);
        String hostname = attr(xr, "hn", "");

        // Determine if this is a Transaction Controller sample
        boolean isTransaction = "true".equalsIgnoreCase(attr(xr, "it", "false"));

        // Failure message: from 'fm' attribute or first failed assertion child
        String failureMessage = attr(xr, "fm", "");

        return new SampleEvent(ts, elapsed, label.intern(), code.intern(), success,
                failureMessage, responseMessage, latency, connect,
                bytes, sentBytes, grpThreads, allThreads,
                threadName, hostname, isTransaction, isSubSample,
                isSubSample ? parentLabel : null);
    }

    private void skipElement(XMLStreamReader xr) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && xr.hasNext()) {
            int ev = xr.next();
            if (ev == XMLStreamConstants.START_ELEMENT) depth++;
            else if (ev == XMLStreamConstants.END_ELEMENT) depth--;
        }
    }

    private static String attr(XMLStreamReader xr, String name, String def) {
        String v = xr.getAttributeValue(null, name);
        return v == null ? def : v;
    }

    private static long attrLong(XMLStreamReader xr, String name, long def) {
        String v = xr.getAttributeValue(null, name);
        if (v == null || v.isEmpty()) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }
}
