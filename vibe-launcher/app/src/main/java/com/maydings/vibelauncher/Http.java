package com.maydings.vibelauncher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Plain GET requests for the backdrop sources (artwork, TMDB, YouTube, Plex). Worker threads only. */
final class Http {

    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;

    private Http() {
    }

    /** The body of a 200 reply, at most maxBytes long; anything else is an IOException. */
    static byte[] get(String url, Map<String, String> headers, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in, maxBytes);
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] get(String url, int maxBytes) throws IOException {
        return get(url, Collections.emptyMap(), maxBytes);
    }

    /** A text (JSON) reply as a string. */
    static String getText(String url, Map<String, String> headers) throws IOException {
        return new String(get(url, headers, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
    }

    /** All of a stream, refusing more than maxBytes (a runaway download). */
    static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new IOException("over " + maxBytes + " bytes");
        }
        return out.toByteArray();
    }
}
