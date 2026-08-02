package io.forge.turviforge.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for JtlCsvParser (M1). */
class JtlCsvParserTest {

    @Test
    void parsesStandardHeader() throws Exception {
        String csv = "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n"
                + "1700000000000,120,Login,200,OK,TG1 1-1,text,true,,1024,256,10,10,http://x,100,0,5\n"
                + "1700000001000,250,Search,200,OK,TG1 1-2,text,true,,2048,512,10,10,http://y,200,0,8\n";
        List<SampleEvent> events = new ArrayList<>();
        JtlCsvParser.Result r = new JtlCsvParser().parse(new StringReader(csv), events::add);
        assertEquals(2, r.total);
        assertEquals(0, r.malformed);
        assertEquals("Login", events.get(0).label());
        assertEquals(120, events.get(0).elapsedMs());
        assertEquals(200, events.get(0).responseCode().length() > 0 ? Integer.parseInt(events.get(0).responseCode()) : 0);
        assertTrue(events.get(0).success());
        assertEquals(100, events.get(0).latencyMs());
        assertEquals(5, events.get(0).connectMs());
        assertEquals(1024, events.get(0).bytes());
        assertEquals(256, events.get(0).sentBytes());
    }

    @Test
    void handlesQuotedFieldsWithEmbeddedCommas() throws Exception {
        String csv = "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n"
                + "1700000000000,5000,Checkout,502,Bad Gateway,TG1 1-1,text,false,\"Upstream error, host=payments-1\",0,0,10,10,,4000,0,3\n";
        List<SampleEvent> events = new ArrayList<>();
        new JtlCsvParser().parse(new StringReader(csv), events::add);
        assertEquals(1, events.size());
        assertEquals("Upstream error, host=payments-1", events.get(0).failureMessage());
        assertFalse(events.get(0).success());
    }

    @Test
    void toleratesMalformedLines() throws Exception {
        String csv = "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n"
                + "1700000000000,100,OK,200,OK,T,text,true,,1,1,1,1,,1,0,1\n"
                + "GARBAGE_LINE_NO_COMMAS\n"
                + "1700000002000,200,OK2,200,OK,T,text,true,,1,1,1,1,,1,0,1\n";
        List<SampleEvent> events = new ArrayList<>();
        JtlCsvParser.Result r = new JtlCsvParser().parse(new StringReader(csv), events::add);
        assertEquals(3, r.total);
        assertEquals(1, r.malformed);
        assertEquals(2, events.size());
    }

    @Test
    void headerlessFallback() throws Exception {
        // Default column order, no header row
        String csv = "1700000000000,150,Login,200,OK,TG1 1-1,text,true,,1024,256,5,5,http://x,120,0,4\n";
        List<SampleEvent> events = new ArrayList<>();
        JtlCsvParser.Result r = new JtlCsvParser().parse(new StringReader(csv), events::add);
        assertEquals(1, r.total);
        assertEquals("Login", events.get(0).label());
        assertEquals(150, events.get(0).elapsedMs());
    }

    @Test
    void parsesGzipFile(@TempDir Path tmp) throws Exception {
        String csv = "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n"
                + "1700000000000,100,GzLabel,200,OK,T,text,true,,1,1,1,1,,1,0,1\n";
        Path gz = tmp.resolve("test.jtl.gz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        List<SampleEvent> events = new ArrayList<>();
        JtlCsvParser.Result r = new JtlCsvParser().parse(gz, events::add);
        assertEquals(1, r.total);
        assertEquals("GzLabel", events.get(0).label());
    }

    @Test
    void epochSecondsTimestamp() throws Exception {
        String csv = "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n"
                + "1700000000,100,SecLabel,200,OK,T,text,true,,1,1,1,1,,1,0,1\n";
        List<SampleEvent> events = new ArrayList<>();
        new JtlCsvParser().parse(new StringReader(csv), events::add);
        // 10-digit => epoch seconds, converted to ms
        assertEquals(1700000000000L, events.get(0).timestampMs());
    }
}
