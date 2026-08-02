package io.forge.reportforge.testdata;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Deterministic synthetic JTL: 60s ramp to 200 threads, 8min steady, 60s ramp-down.
 *  Latency degrades and 502/timeout errors appear beyond ~160 threads. */
public final class SyntheticJtl {
    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "synthetic.jtl");
        long start = 1753500000000L;
        Random rnd = new Random(42);
        String[] labels = {"Login", "Search Products", "Product Detail", "Add To Cart", "Checkout", "Payment Gateway"};
        int[] base = {120, 260, 180, 150, 420, 650};
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n");
            long durMs = 10 * 60_000;
            for (long t = 0; t < durMs; t += 40) {
                int threads = threadsAt(t, durMs);
                double load = threads / 200.0;
                int emits = Math.max(1, threads / 22);
                for (int k = 0; k < emits; k++) {
                    int li = rnd.nextInt(labels.length);
                    double degrade = threads > 160 ? Math.pow((threads - 160) / 40.0, 2) * 2.2 : 0;
                    int elapsed = (int) Math.max(8, base[li] * (0.7 + rnd.nextGaussian() * 0.18 + load * 0.5 + degrade)
                            + (rnd.nextInt(1000) == 0 ? 4000 + rnd.nextInt(6000) : 0));
                    boolean err = false; String code = "200", msg = "OK", fail = "";
                    if (threads > 165 && li >= 4 && rnd.nextDouble() < 0.10 * ((threads - 165) / 35.0)) {
                        err = true; code = "502"; msg = "Bad Gateway";
                        fail = "Upstream connect error to payments-svc-" + (10 + rnd.nextInt(20)) + ":8443, txn " + Long.toHexString(rnd.nextLong());
                        elapsed = 30000 + rnd.nextInt(2500);
                    } else if (rnd.nextDouble() < 0.002) {
                        err = true; code = "500"; msg = "Internal Server Error";
                        fail = "NullPointerException at OrderService.java:" + (100 + rnd.nextInt(400));
                    }
                    int latency = (int) (elapsed * 0.85);
                    int connect = 2 + rnd.nextInt(8);
                    String label = labels[li];
                    w.write((start + t) + "," + elapsed + "," + label + "," + code + "," + msg
                            + ",TG1 1-" + (1 + rnd.nextInt(Math.max(1, threads))) + ",text," + !err + ","
                            + (fail.isEmpty() ? "" : "\"" + fail + "\"") + ","
                            + (800 + rnd.nextInt(9000)) + "," + (200 + rnd.nextInt(400)) + ","
                            + threads + "," + threads + ",https://shop.example/" + li + ","
                            + latency + ",0," + connect + "\n");
                }
            }
        }
        System.out.println("wrote " + out + " (" + Files.size(out) / 1024 + " KB)");
    }

    static int threadsAt(long t, long dur) {
        long rampUp = 60_000, rampDown = 60_000;
        if (t < rampUp) return (int) Math.max(1, 200 * t / rampUp);
        if (t > dur - rampDown) return (int) Math.max(1, 200 * (dur - t) / rampDown);
        return 200;
    }
}
