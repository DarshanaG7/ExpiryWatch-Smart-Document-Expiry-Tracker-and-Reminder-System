import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/** Smart Document Expiry Alert System - REST backend using only the JDK (Java 17+). */
public class DocExpiryServer {

    record Doc(int id, String name, String type, String expiry, int reminder) {
        long days() { return ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(expiry)); }
        String status() {
            long d = days();
            return d < 0 ? "expired" : d <= 7 ? "critical" : d <= 15 ? "warning" : d <= 30 ? "upcoming" : "safe";
        }
        boolean alert() { return days() <= reminder; }
        String json() {
            return "{\"id\":" + id + ",\"name\":\"" + esc(name) + "\",\"type\":\"" + esc(type)
                + "\",\"expiryDate\":\"" + expiry + "\",\"reminderDays\":" + reminder
                + ",\"daysLeft\":" + days() + ",\"status\":\"" + status() + "\",\"alert\":" + alert() + "}";
        }
    }

    static final Path DATA = Path.of("data.txt");
    static final Path PUBLIC = Path.of("public");
    static final List<Doc> docs = new ArrayList<>();
    static int nextId = 1;
    static int defaultReminder = 30;

    public static void main(String[] args) throws IOException {
        load();
       HttpServer server = HttpServer.create(new InetSocketAddress(8090), 0);
        server.createContext("/api", ex -> handle(ex, true));
        server.createContext("/", ex -> handle(ex, false));
        server.start();
        System.out.println("Document Expiry Alert running at http://localhost:8090");
    }

    static synchronized void handle(HttpExchange ex, boolean api) throws IOException {
        try {
            if (api) route(ex); else serveStatic(ex);
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json", "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", "{\"error\":\"Server error\"}");
        } finally {
            ex.close();
        }
    }

    static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(), m = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (path.equals("/api/documents")) {
            if (m.equals("GET")) {
                send(ex, 200, "application/json", "[" + sorted().stream().map(Doc::json).collect(Collectors.joining(",")) + "]");
            } else if (m.equals("POST")) {
                Doc d = parse(nextId, body);
                docs.add(d); nextId++; save();
                send(ex, 201, "application/json", d.json());
            } else send(ex, 405, "application/json", "{\"error\":\"Method not allowed\"}");

        } else if (path.matches("/api/documents/\\d+")) {
            int id = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            int idx = IntStream.range(0, docs.size()).filter(i -> docs.get(i).id() == id).findFirst().orElse(-1);
            if (idx < 0) { send(ex, 404, "application/json", "{\"error\":\"Document not found\"}"); return; }
            if (m.equals("PUT")) {
                Doc d = parse(id, body);
                docs.set(idx, d); save();
                send(ex, 200, "application/json", d.json());
            } else if (m.equals("DELETE")) {
                docs.remove(idx); save();
                send(ex, 204, "application/json", "");
            } else send(ex, 405, "application/json", "{\"error\":\"Method not allowed\"}");

        } else if (path.equals("/api/dashboard") && m.equals("GET")) {
            long expired = count(d -> d.days() < 0), w7 = count(d -> d.days() >= 0 && d.days() <= 7),
                 w15 = count(d -> d.days() >= 0 && d.days() <= 15), w30 = count(d -> d.days() >= 0 && d.days() <= 30);
            send(ex, 200, "application/json", "{\"total\":" + docs.size() + ",\"expired\":" + expired
                + ",\"within7\":" + w7 + ",\"within15\":" + w15 + ",\"within30\":" + w30
                + ",\"safe\":" + count(d -> d.days() > 30) + ",\"alerts\":" + count(Doc::alert) + "}");

        } else if (path.equals("/api/settings")) {
            if (m.equals("PUT")) {
                defaultReminder = Math.max(1, Math.min(365, num(body, "defaultReminderDays", 30)));
                save();
            }
            send(ex, 200, "application/json", "{\"defaultReminderDays\":" + defaultReminder + "}");

        } else if (path.equals("/api/report") && m.equals("GET")) {
            StringBuilder csv = new StringBuilder("Name,Type,Expiry Date,Days Left,Status,Reminder Days\n");
            for (Doc d : sorted())
                csv.append('"').append(d.name().replace("\"", "\"\"")).append("\",").append(d.type()).append(',')
                   .append(d.expiry()).append(',').append(d.days()).append(',').append(d.status()).append(',')
                   .append(d.reminder()).append('\n');
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"expiry-report-" + LocalDate.now() + ".csv\"");
            send(ex, 200, "text/csv; charset=utf-8", csv.toString());

        } else send(ex, 404, "application/json", "{\"error\":\"Unknown endpoint\"}");
    }

    // ---------- helpers ----------
    static List<Doc> sorted() {
        return docs.stream().sorted(Comparator.comparingLong(Doc::days)).collect(Collectors.toList());
    }
    static long count(java.util.function.Predicate<Doc> p) { return docs.stream().filter(p).count(); }

    static Doc parse(int id, String body) {
        String name = str(body, "name").trim(), type = str(body, "type").trim(), expiry = str(body, "expiryDate").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Document name is required");
        if (type.isEmpty()) type = "Other";
        try { LocalDate.parse(expiry); } catch (Exception e) { throw new IllegalArgumentException("Expiry date must be YYYY-MM-DD"); }
        int reminder = Math.max(1, Math.min(365, num(body, "reminderDays", defaultReminder)));
        return new Doc(id, name.replace("|", "/").replaceAll("[\\r\\n]", " "), type.replace("|", "/"), expiry, reminder);
    }
    static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "";
    }
    static int num(String json, String key, int fallback) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?(\\d+)\"?").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }
    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\r\\n\\t]", " ");
    }

    // ---------- persistence (plain text file) ----------
    static void save() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("#" + defaultReminder);
        for (Doc d : docs) lines.add(d.id() + "|" + d.name() + "|" + d.type() + "|" + d.expiry() + "|" + d.reminder());
        Files.write(DATA, lines, StandardCharsets.UTF_8);
    }
    static void load() throws IOException {
        if (!Files.exists(DATA)) {
            String t = LocalDate.now().toString();
            docs.add(new Doc(1, "Driving Licence", "Licence", LocalDate.now().plusDays(6).toString(), 30));
            docs.add(new Doc(2, "Passport", "Passport", LocalDate.now().plusDays(120).toString(), 90));
            docs.add(new Doc(3, "Car Insurance Policy", "Insurance", LocalDate.now().plusDays(22).toString(), 30));
            docs.add(new Doc(4, "Pollution Certificate (PUC)", "Pollution Certificate", LocalDate.now().minusDays(3).toString(), 15));
            nextId = 5; save(); return;
        }
        for (String line : Files.readAllLines(DATA, StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) { defaultReminder = Integer.parseInt(line.substring(1).trim()); continue; }
            String[] p = line.split("\\|");
            if (p.length < 5) continue;
            int id = Integer.parseInt(p[0]);
            docs.add(new Doc(id, p[1], p[2], p[3], Integer.parseInt(p[4])));
            nextId = Math.max(nextId, id + 1);
        }
    }

    // ---------- static files & responses ----------
    static void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = PUBLIC.resolve(path.substring(1)).normalize();
        if (!file.startsWith(PUBLIC) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Not found"); return;
        }
        String ct = path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "application/javascript" : "text/html";
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().add("Content-Type", ct + "; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
    }
    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (!ex.getResponseHeaders().containsKey("Content-Type")) ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
        if (code != 204) ex.getResponseBody().write(bytes);
    }
}
