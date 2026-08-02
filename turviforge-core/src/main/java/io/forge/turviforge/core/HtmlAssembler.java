package io.forge.turviforge.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * M3 assembly (FR-301): one self-contained HTML file.
 * UI assets (echarts.min.js, app.js, app.css) are classpath resources under /ui/.
 */
public final class HtmlAssembler {

    public String assemble(String reportJson, String title) throws IOException {
        String echarts = resource("/ui/echarts.min.js");
        String appJs = resource("/ui/app.js");
        String appCss = resource("/ui/app.css");
        // </script> inside the JSON payload would terminate the data block early.
        String safeJson = reportJson.replace("</", "<\\/");
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + escape(title) + " — TurviForge</title>\n"
                + "<style>\n" + appCss + "\n</style>\n</head>\n<body>\n"
                + "<div id=\"root\"></div>\n"
                + "<script id=\"rf-data\" type=\"application/json\">" + safeJson + "</script>\n"
                + "<script>" + echarts + "</script>\n"
                + "<script>\n" + appJs + "\n</script>\n</body>\n</html>\n";
    }

    private String resource(String path) throws IOException {
        try (InputStream in = HtmlAssembler.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing UI resource on classpath: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
