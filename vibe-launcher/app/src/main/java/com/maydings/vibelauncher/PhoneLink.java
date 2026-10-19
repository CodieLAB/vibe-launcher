package com.maydings.vibelauncher;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny web page on the home network for typing settings on a phone instead of the remote. Runs only
 * while the settings dialog is open; the link carries a random code, so only someone who can see the
 * TV's QR code can send anything.
 */
final class PhoneLink {

    interface Listener {
        /** On the main thread, with the fields the phone filled in (blank ones left out). */
        void onReceived(Map<String, String> fields);
    }

    private static final int PORT = 8765;
    private static final int MAX_BODY = 8 * 1024;

    private final Listener listener;
    private final VibeSettings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String code;
    private ServerSocket server;
    private String url;

    PhoneLink(Listener listener, VibeSettings settings) {
        this.listener = listener;
        this.settings = settings;
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append("abcdefghjkmnpqrstuvwxyz23456789".charAt(r.nextInt(31)));
        code = sb.toString();
    }

    /** Starts listening; returns the page's address, or null when offline or the port is taken. */
    String start() {
        String ip = localIp();
        if (ip == null) return null;
        try {
            server = new ServerSocket(PORT);
        } catch (IOException e) {
            try {
                server = new ServerSocket(0);   // something else has the usual port
            } catch (IOException e2) {
                return null;
            }
        }
        url = "http://" + ip + ":" + server.getLocalPort() + "/" + code;
        Thread t = new Thread(this::serve, "PhoneLink");
        t.setDaemon(true);
        t.start();
        return url;
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(5000);
                handle(s);
            } catch (IOException | RuntimeException e) {
                // A dropped connection or the server closing: carry on, or the loop ends.
            }
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] request = head.split("\r\n", 2)[0].split(" ");
        if (request.length < 2 || !request[1].equals("/" + code)) {
            respond(s, "404 Not Found", page("This link has expired. Scan the code on the TV again."));
            return;
        }
        if (request[0].equals("POST")) {
            Map<String, String> fields = parseForm(readBody(in, contentLength(head)));
            main.post(() -> listener.onReceived(fields));
            respond(s, "200 OK", page("Sent. The keys are saved on your TV."));
        } else {
            respond(s, "200 OK", form());
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b, matched = 0;
        while ((b = in.read()) != -1) {
            out.write(b);
            matched = (b == '\r' || b == '\n') ? matched + 1 : 0;
            if (matched == 4) return out.toString("UTF-8");
            if (out.size() > MAX_BODY) return null;
        }
        return null;
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try {
                    return Math.min(MAX_BODY, Integer.parseInt(line.substring(15).trim()));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String readBody(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int n = 0, r;
        while (n < length && (r = in.read(buf, n, length - n)) > 0) n += r;
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim();
                if (!value.isEmpty()) fields.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), value);
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }
        return fields;
    }

    private static void respond(Socket s, String status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static final String STYLE = "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<style>body{font:17px system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:24px;"
            + "max-width:480px}h1{font-weight:500;font-size:24px}label{display:block;margin:20px 0 6px;color:#aaa}"
            + "input{box-sizing:border-box;width:100%;padding:12px;font:inherit;background:#222;color:#fff;"
            + "border:1px solid #444;border-radius:10px}button{margin-top:28px;width:100%;padding:14px;"
            + "font:inherit;font-weight:600;border:0;border-radius:12px;background:#fff;color:#000}"
            + "p{color:#aaa}</style>";

    private String form() {
        return "<!doctype html><title>Vibe Launcher</title>" + STYLE
                + "<h1>Vibe Launcher settings</h1><p>Paste your keys, then tap Send. Leave a field empty to keep "
                + "what the TV already has.</p><form method=post action='/" + code + "'>"
                + field("tmdbKey", "TMDB API key", settings.tmdbKey())
                + field("youtubeKey", "YouTube API key", settings.youtubeKey())
                + field("plexToken", "Plex token", settings.plexToken())
                + "<button>Send to TV</button></form>";
    }

    /**
     * A field showing what the TV has saved for it, hidden but for the last four characters (anyone on
     * the network who sees the QR code can open this page). Left empty, the saved value stays.
     */
    private static String field(String name, String label, String saved) {
        String hint = saved.isEmpty() ? "Not set"
                : "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" + escape(saved.substring(Math.max(0, saved.length() - 4)));
        return "<label for=" + name + ">" + label + "</label><input id=" + name + " name=" + name
                + " placeholder=\"" + hint + "\" autocomplete=off autocapitalize=off spellcheck=false>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String page(String message) {
        return "<!doctype html><title>Vibe Launcher</title>" + STYLE + "<h1>Vibe Launcher</h1><p>" + message + "</p>";
    }

    /** The QR code for the page's address, black on white, size x size pixels. */
    static Bitmap qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 0));
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }

    /** This device's IPv4 address on the home network. */
    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }
}
