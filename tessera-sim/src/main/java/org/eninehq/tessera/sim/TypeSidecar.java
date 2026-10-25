package org.eninehq.tessera.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eninehq.tessera.ir.Signal;

/**
 * Writes {@code <wave>.types.json}: for every traced signal its Java type, enum literals, the
 * bundle field it belongs to and its source line, so viewers and scripts can show Java-level
 * types instead of raw bits.
 */
public final class TypeSidecar {
    private TypeSidecar() {}

    public static Path write(Netlist net, Path wave) throws IOException {
        Path out = wave.resolveSibling(wave.getFileName() + ".types.json");
        var sb = new StringBuilder("{\n  \"signals\": {\n");
        boolean first = true;
        for (var e : net.signalsByPath().entrySet()) {
            Signal s = e.getValue();
            if (net.slotOf(s) < 0) continue;
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    ").append(str(e.getKey())).append(": {\"width\": ").append(s.width())
                    .append(", \"signed\": ").append(s.signed())
                    .append(", \"kind\": ").append(str(s.kind().name().toLowerCase()));
            var ti = s.typeInfo();
            if (ti != null) {
                sb.append(", \"javaType\": ").append(str(ti.javaType()));
                if (!ti.enumLiterals().isEmpty()) {
                    sb.append(", \"literals\": [");
                    for (int i = 0; i < ti.enumLiterals().size(); i++)
                        sb.append(i > 0 ? ", " : "").append(str(ti.enumLiterals().get(i)));
                    sb.append("]");
                }
                if (ti.bundle() != null) sb.append(", \"bundle\": ").append(str(ti.bundle()));
                if (ti.field() != null) sb.append(", \"field\": ").append(str(ti.field()));
            }
            if (s.loc() != null && s.loc().line() > 0)
                sb.append(", \"source\": ").append(str(s.loc().file() + ":" + s.loc().line()));
            sb.append("}");
        }
        sb.append("\n  }\n}\n");
        Files.writeString(out, sb);
        return out;
    }

    static String str(String s) {
        var sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
