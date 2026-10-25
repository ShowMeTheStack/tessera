package org.eninehq.tessera.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The parts a studio drawing is made of: Tessera's primitive operations, ports, registers and
 * timing helpers. Each part type lists its parameters and, for given parameter values, its pins.
 * The browser draws the palette and the inspector from {@link #catalogue()}; {@link CodeGen}
 * turns parts into Tessera builder code.
 */
public final class Parts {
    private Parts() {}

    public enum Dir { IN, OUT }

    /** A pin: its name, direction and the side of the symbol it sits on (l, r, t, b). */
    public record Pin(String name, Dir dir, String side, String label) {
        static Pin in(String name, String label) { return new Pin(name, Dir.IN, "l", label); }
        static Pin out(String name, String label) { return new Pin(name, Dir.OUT, "r", label); }
        static Pin bottom(String name, String label) { return new Pin(name, Dir.IN, "b", label); }

        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("dir", dir == Dir.IN ? "in" : "out");
            m.put("side", side);
            m.put("label", label);
            return m;
        }
    }

    /** A parameter: kind is int, bool, enum, name, text or seconds. */
    public record Param(String name, String kind, Object dflt, Long min, Long max, List<String> choices, String label, String help) {
        static Param integer(String name, long dflt, long min, long max, String label, String help) {
            return new Param(name, "int", dflt, min, max, List.of(), label, help);
        }
        static Param choice(String name, String dflt, List<String> choices, String label, String help) {
            return new Param(name, "enum", dflt, null, null, choices, label, help);
        }
        static Param name(String dflt, String help) {
            return new Param("name", "name", dflt, null, null, List.of(), "Name", help);
        }

        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("kind", kind);
            m.put("default", dflt);
            if (min != null) m.put("min", min);
            if (max != null) m.put("max", max);
            if (!choices.isEmpty()) m.put("choices", choices);
            m.put("label", label);
            m.put("help", help);
            return m;
        }
    }

    /**
     * A part type. {@code symbol} names how the browser draws it (port, const, gate, not, reduce,
     * box, mux, reg, timing); {@code pins} gives the pins for a set of parameter values.
     */
    public record Type(String type, String category, String title, String symbol, String help, List<Param> params,
                       Function<Map<String, Object>, List<Pin>> pins) {
        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("type", type);
            m.put("category", category);
            m.put("title", title);
            m.put("symbol", symbol);
            m.put("help", help);
            var ps = new ArrayList<Object>();
            for (var p : params) ps.add(p.json());
            m.put("params", ps);
            return m;
        }
    }

    public static final int MAX_WIDTH = 4096;
    private static final Map<String, Type> TYPES = new LinkedHashMap<>();

    static int intParam(Map<String, Object> params, String name, int dflt) {
        Object v = params == null ? null : params.get(name);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
        }
        return dflt;
    }

    static String strParam(Map<String, Object> params, String name, String dflt) {
        Object v = params == null ? null : params.get(name);
        return v == null ? dflt : v.toString();
    }

    private static List<Pin> inputs(int n, String y) {
        var l = new ArrayList<Pin>();
        for (int k = 0; k < n; k++) l.add(Pin.in("in" + k, ""));
        l.add(Pin.out("y", y));
        return l;
    }

    /** Parts with registers inside: each may run in a clock domain of its own. */
    static final Set<String> SEQUENTIAL = Set.of("reg", "mem", "fsm", "counter", "edge", "debounce", "delay", "lfsr", "block", "module", "code", "sync");

    private static void add(Type t) {
        if (SEQUENTIAL.contains(t.type())) {
            var ps = new ArrayList<>(t.params());
            ps.add(new Param("domain", "text", "", null, null, List.of(), "Clock domain",
                    "Empty: the design's clock. Otherwise the name of a Clock domain part: the registers in this part take that clock's edges."));
            t = new Type(t.type(), t.category(), t.title(), t.symbol(), t.help(), List.copyOf(ps), t.pins());
        }
        TYPES.put(t.type(), t);
    }

    private static Param width(long dflt, String help) {
        return Param.integer("width", dflt, 1, MAX_WIDTH, "Width (bits)", help);
    }

    private static Param inputCount() {
        return Param.integer("inputs", 2, 2, 16, "Inputs", "How many operands; all must have the same width.");
    }

    static {
        // ---------------------------------------------------------------- ports
        add(new Type("in", "Ports", "Input port", "port", "A port of the module: input(name, type).",
                List.of(Param.name("a", "The port's name in the module."), width(1, "1 bit is a Bool."),
                        Param.choice("look", "port", List.of("port", "switches", "buttons"), "Drawn as",
                                "A port, or a row of switches (click one to flip its bit) or push buttons (a bit is 1 while held): the same input in the Java.")),
                p -> List.of(Pin.out("y", ""))));
        add(new Type("out", "Ports", "Output port", "port", "A port of the module: output(name, type).set(value). A narrower value is zero-extended; a wider one is an error.",
                List.of(Param.name("y", "The port's name in the module."), width(1, "1 bit is a Bool."),
                        Param.choice("look", "port", List.of("port", "leds", "hex"), "Drawn as",
                                "A port, or a row of LEDs (one a bit) or hexadecimal digits (four bits each), lit by the running value: the same output in the Java.")),
                p -> List.of(Pin.in("a", ""))));
        add(new Type("const", "Ports", "Constant", "const", "A literal: UInt.lit(value, width). Width 0 means the smallest that holds the value.",
                List.of(new Param("value", "text", "0", null, null, List.of(), "Value", "Decimal, or 0x… hex, or 0b… binary."),
                        Param.integer("width", 0, 0, MAX_WIDTH, "Width (bits)", "0: as narrow as the value allows.")),
                p -> List.of(Pin.out("y", ""))));

        // ---------------------------------------------------------------- logic
        for (var g : List.of("and", "or", "xor", "nand", "nor", "xnor"))
            add(new Type(g, "Logic", g.toUpperCase(), "gate", "Bitwise " + g + " of operands of equal width.",
                    List.of(inputCount()), p -> inputs(intParam(p, "inputs", 2), "")));
        add(new Type("not", "Logic", "NOT", "not", "Bitwise complement: a.not().", List.of(),
                p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));
        add(new Type("reduce", "Logic", "Reduce", "reduce", "Reduction to one bit: orR, andR or xorR (parity).",
                List.of(Param.choice("op", "or", List.of("or", "and", "xor"), "Operation", "orR: any bit set; andR: all bits set; xorR: odd parity.")),
                p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));

        // ---------------------------------------------------------------- arithmetic
        add(new Type("add", "Arithmetic", "Add", "box", "a + b. Widen: one bit wider than the wider operand (never overflows); wrap: the wider operand's width.",
                List.of(Param.choice("mode", "widen", List.of("widen", "wrap"), "Result width", "widen: add(); wrap: addWrap().")),
                p -> List.of(Pin.in("a", "a"), Pin.in("b", "b"), Pin.out("y", "+"))));
        add(new Type("sub", "Arithmetic", "Subtract", "box", "a − b. Widen: the top bit is set when b > a; wrap: modulo the wider operand's width.",
                List.of(Param.choice("mode", "wrap", List.of("widen", "wrap"), "Result width", "widen: sub(); wrap: subWrap().")),
                p -> List.of(Pin.in("a", "a"), Pin.in("b", "b"), Pin.out("y", "−"))));
        add(new Type("mul", "Arithmetic", "Multiply", "box", "a × b, as wide as both operands together.", List.of(),
                p -> List.of(Pin.in("a", "a"), Pin.in("b", "b"), Pin.out("y", "×"))));
        add(new Type("cmp", "Arithmetic", "Compare", "box", "Unsigned comparison, one bit.",
                List.of(Param.choice("op", "eq", List.of("eq", "ne", "lt", "le", "gt", "ge"), "Comparison", "a == b, !=, <, <=, >, >=.")),
                p -> List.of(Pin.in("a", "a"), Pin.in("b", "b"), Pin.out("y", ""))));
        add(new Type("shift", "Arithmetic", "Shift (constant)", "box", "Shift by a constant: left grows the value by n bits; right keeps the width.",
                List.of(Param.choice("dir", "left", List.of("left", "right"), "Direction", ""),
                        Param.integer("amount", 1, 0, MAX_WIDTH, "Amount", "Bits to shift by.")),
                p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));
        add(new Type("dshift", "Arithmetic", "Shift (variable)", "box", "Shift by a hardware amount; the width is unchanged and bits shifted out are lost.",
                List.of(Param.choice("dir", "left", List.of("left", "right"), "Direction", "")),
                p -> List.of(Pin.in("a", "a"), Pin.in("n", "n"), Pin.out("y", ""))));

        // ---------------------------------------------------------------- selection
        add(new Type("mux", "Select", "Multiplexer", "mux", "Selects one input by sel: Hw.mux for two inputs, Hw.index for more. Inputs are extended to the widest.",
                List.of(Param.integer("inputs", 2, 2, 64, "Inputs", "Two inputs select with a 1-bit sel; n inputs need ⌈log2 n⌉ bits.")),
                p -> {
                    int n = intParam(p, "inputs", 2);
                    var l = new ArrayList<Pin>();
                    for (int k = 0; k < n; k++) l.add(Pin.in("in" + k, Integer.toString(k)));
                    l.add(Pin.bottom("sel", "sel"));
                    l.add(Pin.out("y", ""));
                    return l;
                }));

        // ---------------------------------------------------------------- bits
        add(new Type("slice", "Bits", "Slice", "box", "Bits hi down to lo: a.bits(hi, lo); one bit is a.bit(i).",
                List.of(Param.integer("hi", 0, 0, MAX_WIDTH - 1, "High bit", ""), Param.integer("lo", 0, 0, MAX_WIDTH - 1, "Low bit", "")),
                p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));
        add(new Type("concat", "Bits", "Concatenate", "box", "Joins its inputs into one value, the top input most significant: Hw.cat.",
                List.of(Param.integer("inputs", 2, 2, 32, "Inputs", "")), p -> inputs(intParam(p, "inputs", 2), "{}")));
        add(new Type("resize", "Bits", "Resize", "box", "To a given width: zero-extend (zext) when wider, keep the low bits (trunc) when narrower.",
                List.of(width(8, "The new width.")), p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));

        // ---------------------------------------------------------------- state
        add(new Type("reg", "State", "Register", "reg", "A register in the module's clock domain: reg(name, type, init). It takes d on every clock edge, or only when en is 1.",
                List.of(Param.name("r", "The register's name in the hardware and in the waves."), width(8, ""),
                        new Param("init", "text", "0", null, null, List.of(), "Reset value", "The value after reset."),
                        new Param("enable", "bool", false, null, null, List.of(), "Enable input", "Add an en pin: the register keeps its value while en is 0.")),
                p -> {
                    var l = new ArrayList<Pin>(List.of(Pin.in("d", "D")));
                    if (Boolean.TRUE.equals(p.get("enable")) || "true".equals(String.valueOf(p.get("enable")))) l.add(Pin.in("en", "EN"));
                    l.add(Pin.out("q", "Q"));
                    return l;
                }));

        add(new Type("mem", "State", "Memory", "mem", "A memory of depth words: mem(name, type, depth). Reads are combinational (read) or one cycle later (readSync); "
                + "a write takes wdata at addr on the clock edge when we is 1. Without writes it is a ROM of its initial words.",
                List.of(Param.name("m", "The memory's name in the hardware."), width(8, "The width of one word."),
                        Param.integer("depth", 16, 2, 1 << 20, "Depth (words)", "The address is ⌈log2 depth⌉ bits."),
                        Param.choice("read", "async", List.of("async", "sync"), "Read", "async: data follows the address in the same cycle; sync: data appears a cycle after the address (block RAM)."),
                        new Param("write", "bool", true, null, null, List.of(), "Write port", "Without one, the memory is a ROM."),
                        new Param("init", "text", "", null, null, List.of(), "Initial words", "Words from address 0, separated by spaces or commas (0x.., 0b.., or decimal); the rest are 0.")),
                p -> {
                    var l = new ArrayList<Pin>(List.of(Pin.in("addr", "addr")));
                    if (!"false".equals(String.valueOf(p.getOrDefault("write", true)))) {
                        l.add(Pin.in("wdata", "wdata"));
                        l.add(Pin.in("we", "we"));
                    }
                    l.add(Pin.out("rdata", "rdata"));
                    return l;
                }));
        add(new Type("table", "Logic", "Truth table", "box", "Logic given as a truth table over named 1-bit inputs and outputs: TruthTable from the standard library. "
                + "A - in an input column matches 0 and 1; rows that are not listed give 0; overlapping rows must agree. Edit it in the inspector.",
                List.of(new Param("inputs", "text", "a b", null, null, List.of(), "Inputs", "1-bit input names, separated by spaces."),
                        new Param("outputs", "text", "y", null, null, List.of(), "Outputs", "1-bit output names, separated by spaces."),
                        new Param("rows", "lines", "0 1 | 1\n1 0 | 1", null, null, List.of(), "Rows",
                                "One per line: the inputs' values (0, 1 or -), a |, then the outputs' values (0 or 1).")),
                p -> {
                    var l = new ArrayList<Pin>();
                    for (String x : strParam(p, "inputs", "a b").trim().split("\\s+")) if (!x.isEmpty()) l.add(Pin.in(x, x));
                    for (String x : strParam(p, "outputs", "y").trim().split("\\s+")) if (!x.isEmpty()) l.add(Pin.out(x, x));
                    return l;
                }));

        add(new Type("fsm", "State", "State machine", "fsm", "A Moore state machine: a state register and the transitions between its states, "
                + "each taken when its condition holds (the first listed wins). Outputs are 1 in the states named for them. The inspector draws it, with the state it is in now.",
                List.of(Param.name("fsm", "The state register's name in the hardware and in the waves."),
                        new Param("states", "text", "IDLE RUN DONE", null, null, List.of(), "States", "Names, separated by spaces; the first is the state after reset."),
                        new Param("inputs", "text", "go", null, null, List.of(), "Inputs", "1-bit input pins, separated by spaces."),
                        new Param("transitions", "lines", "IDLE -> RUN : go\nRUN -> DONE : !go\nDONE -> IDLE : 1", null, null, List.of(), "Transitions",
                                "One per line: FROM -> TO : condition. A condition uses the inputs with ! (not), & (and), | (or), ^ (xor), parentheses, 1 and 0."),
                        new Param("outputs", "lines", "busy : RUN", null, null, List.of(), "Outputs", "One per line: name : the states in which it is 1.")),
                p -> {
                    var l = new ArrayList<Pin>();
                    for (String in : strParam(p, "inputs", "go").trim().split("\\s+")) if (!in.isEmpty()) l.add(Pin.in(in, in));
                    l.add(Pin.out("state", "state"));
                    for (String line : strParam(p, "outputs", "").split("\\R")) {
                        int c = line.indexOf(':');
                        String n = (c < 0 ? line : line.substring(0, c)).trim();
                        if (!n.isEmpty()) l.add(Pin.out(n, n));
                    }
                    return l;
                }));

        // ---------------------------------------------------------------- timing (needs the module's Clock)
        add(new Type("blink", "Timing", "Blink", "timing", "Timing.blink(clk, half period): 1 for this long, then 0 for as long.",
                List.of(new Param("seconds", "seconds", 0.5, null, null, List.of(), "Half period (s)", "")),
                p -> List.of(Pin.out("y", ""))));
        add(new Type("every", "Timing", "Every", "timing", "Timing.every(clk, period): 1 for one cycle, once per period.",
                List.of(new Param("seconds", "seconds", 1.0, null, null, List.of(), "Period (s)", "")),
                p -> List.of(Pin.out("y", ""))));
    }

    static {
        // ---------------------------------------------------------------- the standard library (org.eninehq.tessera.stdlib.Util)
        add(new Type("counter", "Library", "Counter", "box", "Util.counter(max, inc): counts 0 to max and wraps, advancing when inc is 1; wrap is 1 in the cycle it wraps.",
                List.of(Param.integer("max", 9, 1, Integer.MAX_VALUE, "Counts to", "The last value before it wraps to 0; the value is ⌈log2(max+1)⌉ bits.")),
                p -> List.of(Pin.in("inc", "inc"), Pin.out("value", "value"), Pin.out("wrap", "wrap"))));
        add(new Type("edge", "Library", "Edge detector", "box", "Util.risingEdge or fallingEdge: 1 for one cycle when a changes that way.",
                List.of(Param.choice("kind", "rising", List.of("rising", "falling"), "Edge", "")),
                p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));
        add(new Type("domain", "State", "Clock domain", "box", "clockDomain(name): a clock of its own, with an edge every period ticks of the simulation (the design's clock, named clock, has one every tick unless a Clock domain part named clock says otherwise). "
                + "Parts whose Clock domain is this name take its edges; a value that crosses between domains goes through a Synchroniser, or the CDC check says where it does not.",
                List.of(Param.name("fast", "The domain's name: its clock and reset ports are name_clock and name_reset."),
                        Param.integer("period", 2, 1, 1 << 20, "Period (ticks)", "Ticks of the simulation between this clock's rising edges."),
                        Param.integer("phase", 0, 0, 1 << 20, "First edge (tick)", "The tick of its first edge, less than the period.")),
                p -> List.of()));
        add(new Type("sync", "Library", "Synchroniser", "box", "Hw.synchronize(a, stages): a flip-flop chain in this part's clock domain, for a bit that comes from another domain.",
                List.of(Param.integer("stages", 2, 2, 8, "Stages", "Flip-flops in the chain; two is usual.")),
                p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));
        add(new Type("delay", "Library", "Delay", "box", "Util.delay(a, n): a, n cycles ago (n registers in a row, without reset). Two in a row make a synchroniser.",
                List.of(Param.integer("cycles", 2, 1, 1024, "Cycles", "")),
                p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));
        add(new Type("debounce", "Library", "Debounce", "box", "Util.debounce(a, cycles): changes only once a has been steady this many cycles. Synchronise an asynchronous input first.",
                List.of(Param.integer("cycles", 1000, 1, 1 << 30, "Steady for (cycles)", "")),
                p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));
        add(new Type("lfsr", "Library", "LFSR", "box", "Util.lfsr(width, step): a maximal-length pseudo-random sequence that never reaches 0; it moves on when step is 1.",
                List.of(Param.integer("width", 8, 2, 32, "Width (bits)", "")),
                p -> List.of(Pin.in("step", "step"), Pin.out("y", ""))));
        add(new Type("bitop", "Library", "Bit utility", "box", "popCount (set bits, ⌈log2(w+1)⌉ wide), reverse, toGray or fromGray of a, from Util.",
                List.of(Param.choice("op", "popCount", List.of("popCount", "reverse", "toGray", "fromGray"), "Operation", "")),
                p -> List.of(Pin.in("a", ""), Pin.out("y", ""))));
        add(new Type("onehot", "Library", "One-hot", "box", "Util.oneHot(idx, n): n bits with only bit idx set.",
                List.of(Param.integer("n", 8, 2, 1024, "Outputs (bits)", "")),
                p -> List.of(Pin.in("idx", "idx"), Pin.out("y", ""))));
        add(new Type("prienc", "Library", "Priority encoder", "box", "Util.priorityEncoder(a): the index of the lowest set bit, and valid when any bit is set.",
                List.of(), p -> List.of(Pin.in("a", ""), Pin.out("index", "index"), Pin.out("valid", "valid"))));
    }

    static {
        // ---------------------------------------------------------------- verification
        add(new Type("assert", "Verify", "Assert", "check", "Hw.assertThat(cond, name): cond must be 1 in every cycle after reset. Checked in simulation and by the Prove pane.",
                List.of(Param.name("p1", "The property's name, used in messages and in the Prove pane.")), p -> List.of(Pin.in("cond", ""))));
        add(new Type("assume", "Verify", "Assume", "check", "Hw.assume(cond, name): the prover only considers runs where cond is 1.",
                List.of(Param.name("a1", "The assumption's name.")), p -> List.of(Pin.in("cond", ""))));
        add(new Type("cover", "Verify", "Cover", "check", "Hw.cover(cond, name): the Prove pane finds the first cycle at which cond can be 1.",
                List.of(Param.name("c1", "The cover point's name.")), p -> List.of(Pin.in("cond", ""))));
    }

    public static Type type(String type) {
        return TYPES.get(type);
    }

    public static List<Pin> pins(String type, Map<String, Object> params) {
        var t = TYPES.get(type);
        return t == null ? List.of() : t.pins().apply(params == null ? Map.of() : params);
    }

    /** The catalogue as JSON for the browser: types with params; pins are asked per part through {@code /api/pins}. */
    public static List<Object> catalogue() {
        var l = new ArrayList<Object>();
        for (var t : TYPES.values()) {
            var m = t.json();
            var pins = new ArrayList<Object>();
            for (var p : t.pins().apply(defaults(t))) pins.add(p.json());
            m.put("pins", pins);
            l.add(m);
        }
        return l;
    }

    static Map<String, Object> defaults(Type t) {
        var m = new LinkedHashMap<String, Object>();
        for (var p : t.params()) m.put(p.name(), p.dflt());
        return m;
    }

    static List<Object> pinsJson(String type, Map<String, Object> params) {
        var l = new ArrayList<Object>();
        for (var p : pins(type, params)) l.add(p.json());
        return l;
    }
}
