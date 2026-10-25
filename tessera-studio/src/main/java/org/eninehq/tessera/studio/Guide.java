package org.eninehq.tessera.studio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Tessera Studio Guide, served at {@code /guide}: {@code docs/studio-guide.md} rendered to one page
 * (headings with anchors, paragraphs, lists, tables, code, pictures, links). The guide is found in the
 * repository when the studio runs from it, and otherwise in the bundle, where the build copies it.
 */
final class Guide {
    private Guide() {}

    /** The guide's source and the directory its pictures are in, or null. */
    static Path dir() {
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            Path f = d.resolve("docs/studio-guide.md");
            if (Files.isRegularFile(f)) return f.getParent();
        }
        return null;
    }

    static String markdown() throws IOException {
        Path d = dir();
        if (d != null) return Files.readString(d.resolve("studio-guide.md"));
        try (InputStream in = Guide.class.getResourceAsStream("guide/studio-guide.md")) {
            if (in == null) throw new IOException("the guide is not in the bundle");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A picture of the guide (the name only, no paths), or null. */
    static byte[] picture(String name) throws IOException {
        if (!name.matches("[\\w.-]+\\.png")) return null;
        Path d = dir();
        if (d != null) {
            Path f = d.resolve("studio-guide").resolve(name);
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        }
        try (InputStream in = Guide.class.getResourceAsStream("guide/studio-guide/" + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** The sections, for the command palette: [{id, title}]. */
    static List<Map<String, String>> sections() throws IOException {
        var l = new ArrayList<Map<String, String>>();
        for (String line : markdown().split("\n")) {
            var m = Pattern.compile("^(#{2,3})\\s+(.*)$").matcher(line);
            if (m.matches()) {
                var s = new LinkedHashMap<String, String>();
                s.put("id", slug(m.group(2)));
                s.put("title", plain(m.group(2)));
                l.add(s);
            }
        }
        return l;
    }

    static String page() throws IOException {
        String body = render(markdown());
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Tessera Studio Guide</title><style>
                :root{--bg:#0f1218;--panel:#151a22;--line:#262e3b;--text:#dfe5ee;--muted:#93a0b3;--accent:#5eb6ff;--code:#1b212c}
                @media (prefers-color-scheme:light){:root:not([data-theme=dark]){--bg:#fbfbfd;--panel:#fff;--line:#e2e5ec;--text:#1b2230;--muted:#5d6878;--accent:#1567d3;--code:#f2f4f8}}
                :root[data-theme=light]{--bg:#fbfbfd;--panel:#fff;--line:#e2e5ec;--text:#1b2230;--muted:#5d6878;--accent:#1567d3;--code:#f2f4f8}
                html{scroll-padding-top:16px}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.6 Inter,system-ui,-apple-system,sans-serif}
                main{max-width:860px;margin:0 auto;padding:28px 24px 80px}h1{font-size:30px;margin:0 0 8px}h2{font-size:21px;margin:42px 0 10px;padding-top:6px;border-top:1px solid var(--line)}
                h3{font-size:16.5px;margin:26px 0 6px}a{color:var(--accent)}code{font:13px ui-monospace,SFMono-Regular,Menlo,monospace;background:var(--code);padding:1px 5px;border-radius:4px}
                pre{background:var(--code);padding:12px 14px;border-radius:8px;overflow:auto}pre code{padding:0;background:none}
                table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px}th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}th{color:var(--muted);font-weight:600}
                img{max-width:100%;border:1px solid var(--line);border-radius:8px;margin:10px 0}hr{border:0;border-top:1px solid var(--line);margin:30px 0}
                li{margin:3px 0}.anchor{opacity:0;margin-left:6px;text-decoration:none}h2:hover .anchor,h3:hover .anchor{opacity:.6}
                </style><script>var t=new URLSearchParams(location.search).get('theme');if(t)document.documentElement.dataset.theme=t;</script></head><body><main>
                """ + body + "</main></body></html>";
    }

    // ---------------------------------------------------------------- a small Markdown renderer

    static String render(String md) {
        var out = new StringBuilder();
        String[] lines = md.replace("\r", "").split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String l = lines[i];
            if (l.isBlank()) { i++; continue; }
            if (l.startsWith("```")) {
                var code = new StringBuilder();
                for (i++; i < lines.length && !lines[i].startsWith("```"); i++) code.append(esc(lines[i])).append('\n');
                i++;
                out.append("<pre><code>").append(code).append("</code></pre>\n");
                continue;
            }
            var h = Pattern.compile("^(#{1,4})\\s+(.*)$").matcher(l);
            if (h.matches()) {
                int n = h.group(1).length();
                String id = slug(h.group(2));
                out.append("<h").append(n).append(" id=\"").append(id).append("\">").append(inline(h.group(2)))
                        .append(n > 1 ? "<a class=\"anchor\" href=\"#" + id + "\">#</a>" : "").append("</h").append(n).append(">\n");
                i++;
                continue;
            }
            if (l.trim().equals("---")) { out.append("<hr>\n"); i++; continue; }
            if (l.startsWith("|")) {
                var rows = new ArrayList<String[]>();
                for (; i < lines.length && lines[i].startsWith("|"); i++) rows.add(cells(lines[i]));
                out.append("<table>");
                for (int r = 0; r < rows.size(); r++) {
                    if (r == 1 && rows.get(1)[0].matches("-+")) continue;
                    String tag = r == 0 ? "th" : "td";
                    out.append("<tr>");
                    for (String c : rows.get(r)) out.append('<').append(tag).append('>').append(inline(c)).append("</").append(tag).append('>');
                    out.append("</tr>");
                }
                out.append("</table>\n");
                continue;
            }
            if (l.matches("^\\s*([-*]|\\d+\\.)\\s+.*")) { i = list(lines, i, out); continue; }
            var para = new StringBuilder();
            for (; i < lines.length && !lines[i].isBlank() && !lines[i].startsWith("#") && !lines[i].startsWith("|") && !lines[i].startsWith("```")
                    && !lines[i].matches("^\\s*([-*]|\\d+\\.)\\s+.*") && !lines[i].trim().equals("---"); i++) para.append(lines[i]).append(' ');
            out.append("<p>").append(inline(para.toString().trim())).append("</p>\n");
        }
        return out.toString();
    }

    /** A list and its nested lists (by indentation); returns the line after it. */
    private static int list(String[] lines, int i, StringBuilder out) {
        int indent = lines[i].length() - lines[i].stripLeading().length();
        boolean ordered = lines[i].trim().matches("^\\d+\\..*");
        out.append(ordered ? "<ol>" : "<ul>");
        boolean open = false;
        while (i < lines.length) {
            String l = lines[i];
            if (l.isBlank()) { i++; if (i < lines.length && lines[i].matches("^\\s*([-*]|\\d+\\.)\\s+.*")) continue; break; }
            int ind = l.length() - l.stripLeading().length();
            if (ind > indent && l.trim().matches("^([-*]|\\d+\\.)\\s+.*")) { i = list(lines, i, out); continue; }
            if (ind < indent) break;
            var m = Pattern.compile("^\\s*([-*]|\\d+\\.)\\s+(.*)$").matcher(l);
            if (m.matches() && ind == indent) {
                if (open) out.append("</li>");
                out.append("<li>").append(inline(m.group(2)));
                open = true;
            } else if (ind >= indent) {
                out.append(' ').append(inline(l.trim()));       // a continued item
            } else break;
            i++;
        }
        if (open) out.append("</li>");
        out.append(ordered ? "</ol>\n" : "</ul>\n");
        return i;
    }

    private static String[] cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        var l = new ArrayList<String>();
        var cur = new StringBuilder();
        boolean code = false;
        for (char c : r.toCharArray()) {        // a | inside `code` is not a column
            if (c == '`') code = !code;
            if (c == '|' && !code) { l.add(cur.toString().trim()); cur.setLength(0); } else cur.append(c);
        }
        l.add(cur.toString().trim());
        return l.toArray(String[]::new);
    }

    static String inline(String s) {
        var out = new StringBuilder();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(span(s.substring(last, m.start()))).append("<code>").append(esc(m.group(1))).append("</code>");
            last = m.end();
        }
        out.append(span(s.substring(last)));
        return out.toString();
    }

    private static String span(String s) {
        String t = esc(s);
        t = t.replaceAll("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)", "<img alt=\"$1\" src=\"/guide/$2\" loading=\"lazy\">");
        t = t.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
        t = t.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        t = t.replaceAll("(?<![\\w*])\\*([^*\\s][^*]*)\\*(?![\\w*])", "<i>$1</i>");
        return t;
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    static String plain(String s) { return s.replaceAll("[`*]", ""); }

    static String slug(String s) {
        return plain(s).toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
