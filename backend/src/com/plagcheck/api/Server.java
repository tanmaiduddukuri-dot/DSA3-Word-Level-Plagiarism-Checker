package com.plagcheck.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.plagcheck.PlagiarismChecker;
import com.plagcheck.matching.MatchSpan;
import com.plagcheck.preprocessing.Document;
import com.plagcheck.preprocessing.Token;
import com.plagcheck.preprocessing.Tokenizer;
import com.plagcheck.scoring.ScoreResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small local HTTP bridge in front of the existing plagcheck classes.
 * Exposes:
 *   GET  /api/health         -> {"status":"ok"}
 *   POST /api/check          -> runs PlagiarismChecker and returns JSON
 *
 * No servlet container / framework: com.sun.net.httpserver.HttpServer ships
 * with the JDK itself, so this needs nothing beyond `javac`/`java`.
 */
public final class Server {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8787;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", Server::handleHealth);
        server.createContext("/api/check", Server::handleCheck);
        server.setExecutor(null);
        server.start();
        System.out.println("PlagiarismChecker API listening on http://localhost:" + port);
        System.out.println("  GET  /api/health");
        System.out.println("  POST /api/check");
    }

    private static void handleHealth(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handledPreflight(ex)) return;
        writeJson(ex, 200, "{\"status\":\"ok\"}");
    }

    @SuppressWarnings("unchecked")
    private static void handleCheck(HttpExchange ex) throws IOException {
        addCors(ex);
        if (handledPreflight(ex)) return;

        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            writeJson(ex, 405, "{\"error\":\"Use POST\"}");
            return;
        }

        try {
            String body = readBody(ex);
            Map<String, Object> req = (Map<String, Object>) Json.parse(body);

            Map<String, Object> submissionReq = (Map<String, Object>) req.get("submission");
            List<Object> referencesReq = (List<Object>) req.get("references");
            if (submissionReq == null || referencesReq == null || referencesReq.isEmpty()) {
                writeJson(ex, 400, "{\"error\":\"submission and at least one reference are required\"}");
                return;
            }

            int k = intOrDefault(req.get("k"), 5);
            int minSpan = intOrDefault(req.get("minSpan"), 3);
            boolean stopwords = Boolean.TRUE.equals(req.get("stopwords"));

            Tokenizer tokenizer = new Tokenizer(stopwords);

            String subName = String.valueOf(submissionReq.getOrDefault("name", "submission.txt"));
            String subText = String.valueOf(submissionReq.getOrDefault("text", ""));
            Document submission = tokenizer.tokenizeLines("submission:" + subName, subName, splitLines(subText));

            List<Document> refs = new ArrayList<>();
            for (Object o : referencesReq) {
                Map<String, Object> r = (Map<String, Object>) o;
                String name = String.valueOf(r.getOrDefault("name", "reference.txt"));
                String text = String.valueOf(r.getOrDefault("text", ""));
                refs.add(tokenizer.tokenizeLines("ref:" + name, name, splitLines(text)));
            }

            PlagiarismChecker checker = new PlagiarismChecker(k, minSpan);
            ScoreResult result = checker.check(submission, refs);

            String json = toJson(submission, result);
            writeJson(ex, 200, json);
        } catch (Exception e) {
            StringBuilder err = new StringBuilder();
            err.append("{\"error\":");
            Json.writeString(err, String.valueOf(e.getMessage()));
            err.append("}");
            writeJson(ex, 500, err.toString());
        }
    }

    // ---------------- Response building ----------------

    private static String toJson(Document submission, ScoreResult result) {
        // Aggregate per-source exact/paraphrase token counts directly from the
        // effective (already de-overlapped) spans the checker returned.
        Map<String, int[]> perSourceCounts = new LinkedHashMap<>(); // name -> [exact, para]
        for (MatchSpan span : result.getSpans()) {
            String src = span.getReferenceDocId();
            perSourceCounts.putIfAbsent(src, new int[2]);
            int[] counts = perSourceCounts.get(src);
            if (span.getType().name().equals("EXACT")) counts[0] += span.length();
            else counts[1] += span.length();
        }

        StringBuilder out = new StringBuilder();
        out.append('{');

        out.append("\"submissionName\":"); Json.writeString(out, submission.getId()); out.append(',');
        out.append("\"totalTokens\":").append(result.getTotalTokens()).append(',');
        out.append("\"coveredTokens\":").append(result.getCoveredTokens()).append(',');
        out.append("\"overallSimilarityPercent\":").append(result.getOverallSimilarityPercent()).append(',');

        int exactTotal = 0, paraTotal = 0;
        for (int[] c : perSourceCounts.values()) { exactTotal += c[0]; paraTotal += c[1]; }
        out.append("\"exactTotal\":").append(exactTotal).append(',');
        out.append("\"paraTotal\":").append(paraTotal).append(',');

        // sources: use perSourcePercent for the authoritative percent, counts from spans
        out.append("\"sources\":[");
        boolean first = true;
        for (Map.Entry<String, Double> e : result.getPerSourcePercent().entrySet()) {
            if (!first) out.append(',');
            first = false;
            int[] counts = perSourceCounts.getOrDefault(e.getKey(), new int[2]);
            out.append('{');
            out.append("\"name\":"); Json.writeString(out, e.getKey()); out.append(',');
            out.append("\"percent\":").append(e.getValue()).append(',');
            out.append("\"exact\":").append(counts[0]).append(',');
            out.append("\"para\":").append(counts[1]);
            out.append('}');
        }
        out.append("],");

        // spans: raw evidence list
        out.append("\"spans\":[");
        first = true;
        for (MatchSpan span : result.getSpans()) {
            if (!first) out.append(',');
            first = false;
            out.append('{');
            out.append("\"type\":\"").append(span.getType().name()).append("\",");
            out.append("\"submissionStart\":").append(span.getSubmissionStart()).append(',');
            out.append("\"submissionEnd\":").append(span.getSubmissionEnd()).append(',');
            out.append("\"length\":").append(span.length()).append(',');
            out.append("\"source\":"); Json.writeString(out, span.getReferenceDocId());
            out.append('}');
        }
        out.append("],");

        // highlighted: reconstruct token stream with <mark> tags around matched spans
        out.append("\"highlightedHtml\":");
        Json.writeString(out, buildHighlight(submission, result.getSpans()));

        out.append('}');
        return out.toString();
    }

    private static String buildHighlight(Document doc, List<MatchSpan> spans) {
        // spans are token-index ranges [submissionStart, submissionEnd) into doc's token list
        String[] typeAt = new String[doc.size()];
        for (MatchSpan span : spans) {
            for (int i = span.getSubmissionStart(); i < span.getSubmissionEnd() && i < doc.size(); i++) {
                if (typeAt[i] == null) typeAt[i] = span.getType().name();
            }
        }
        StringBuilder html = new StringBuilder();
        String openType = null;
        for (int i = 0; i < doc.size(); i++) {
            Token t = doc.getToken(i);
            String type = typeAt[i];
            if (!eq(type, openType)) {
                if (openType != null) html.append("</mark>");
                if (type != null) {
                    html.append("<mark class=\"").append("EXACT".equals(type) ? "hl-exact" : "hl-para")
                        .append("\" title=\"").append(type).append("\">");
                }
                openType = type;
            }
            if (i > 0) html.append(' ');
            html.append(escapeHtml(t.getOriginal()));
        }
        if (openType != null) html.append("</mark>");
        return html.toString();
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#039;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------------- Helpers ----------------

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\r\n|\r|\n", -1)) lines.add(l);
        return lines;
    }

    private static int intOrDefault(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static boolean handledPreflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return true;
        }
        return false;
    }

    private static void writeJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
