package org.eninehq.tessera.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eninehq.tessera.core.SourceLoc;
import org.eninehq.tessera.core.TesseraException;

/** Applies the suggested fixes of Tessera diagnostics to source files. */
public final class Fixer {
    private Fixer() {}

    /** What was changed: file, line, the fix, and the line before and after. */
    public record Applied(Path file, int line, String description, String before, String after) {}

    /** The source file a location refers to, searched in {@code roots}. */
    public static Optional<Path> locate(SourceLoc loc, List<Path> roots) {
        String cls = loc.className();
        int dot = cls.lastIndexOf('.');
        String pkg = dot < 0 ? "" : cls.substring(0, dot).replace('.', '/');
        for (var r : roots) {
            Path p = pkg.isEmpty() ? r.resolve(loc.file()) : r.resolve(pkg).resolve(loc.file());
            if (Files.exists(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** Apply the first applicable fix of every diagnostic; returns what changed. */
    public static List<Applied> apply(TesseraException ex, List<Path> roots) throws IOException {
        var out = new ArrayList<Applied>();
        for (var d : ex.diagnostics()) {
            if (d.fixes().isEmpty()) continue;
            var file = locate(d.loc(), roots);
            if (file.isEmpty()) continue;
            var lines = new ArrayList<>(Files.readAllLines(file.get()));
            int idx = d.loc().line() - 1;
            if (idx < 0 || idx >= lines.size()) continue;
            String before = lines.get(idx);
            for (var f : d.fixes()) {
                var m = Pattern.compile(f.find()).matcher(before);
                if (!m.find()) continue;
                String after = m.replaceFirst(f.replace());
                lines.set(idx, after);
                Files.write(file.get(), lines);
                out.add(new Applied(file.get(), idx + 1, f.description(), before.strip(), after.strip()));
                break;
            }
        }
        return out;
    }

    /** Diagnostics as JSON, for editors and other tools. */
    public static String json(TesseraException ex, List<Path> roots) {
        var sb = new StringBuilder("[");
        boolean first = true;
        for (var d : ex.diagnostics()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("\n  {\"file\": ").append(str(locate(d.loc(), roots).map(Path::toString).orElse(d.loc().file())))
                    .append(", \"line\": ").append(d.loc().line())
                    .append(", \"message\": ").append(str(d.message()))
                    .append(", \"fixes\": [");
            for (int i = 0; i < d.fixes().size(); i++) {
                var f = d.fixes().get(i);
                sb.append(i > 0 ? ", " : "").append("{\"description\": ").append(str(f.description()))
                        .append(", \"find\": ").append(str(f.find())).append(", \"replace\": ").append(str(f.replace())).append('}');
            }
            sb.append("]}");
        }
        return sb.append("\n]").toString();
    }

    public static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
