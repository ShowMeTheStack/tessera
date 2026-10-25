package org.eninehq.tessera.studio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The recorded waveforms: for each recorded signal, the cycles at which its value changed and the
 * new values. A window of the record is answered either exactly (every change) or, when there are
 * more changes than the browser has pixel columns, one summary per column: the value at the
 * column's start, the number of changes inside it and the smallest and largest value, so that a
 * million cycles draw as fast as a hundred.
 */
final class Waves {
    /**
     * One signal's changes, in cycle order. Values of up to 63 bits are kept as longs (16 bytes a change
     * with the cycle); only wider signals keep BigIntegers.
     */
    static final class Track {
        final int width;
        long[] at = new long[16];
        private long[] lv;              // width <= 63
        private BigInteger[] bv;        // wider
        int n;

        Track(int width) {
            this.width = width;
            if (width <= 63) lv = new long[16]; else bv = new BigInteger[16];
        }

        BigInteger get(int i) { return lv != null ? BigInteger.valueOf(lv[i]) : bv[i]; }
        String hex(int i) { return lv != null ? Long.toHexString(lv[i]) : bv[i].toString(16); }
        int cmp(int a, int b) { return lv != null ? Long.compare(lv[a], lv[b]) : bv[a].compareTo(bv[b]); }
        private boolean same(int i, BigInteger v) { return lv != null ? v.bitLength() <= 63 && lv[i] == v.longValue() : bv[i].equals(v); }
        private void set(int i, BigInteger v) { if (lv != null) lv[i] = v.longValue(); else bv[i] = v; }

        void add(long cycle, BigInteger v) {
            if (n > 0 && same(n - 1, v)) return;
            if (n > 0 && at[n - 1] == cycle) { set(n - 1, v); if (n > 1 && same(n - 2, v)) n--; return; }
            if (n == at.length) {
                at = Arrays.copyOf(at, n * 2);
                if (lv != null) lv = Arrays.copyOf(lv, n * 2); else bv = Arrays.copyOf(bv, n * 2);
            }
            at[n] = cycle;
            set(n, v);
            n++;
        }

        /** Index of the last change at or before {@code cycle}, or -1. */
        int floor(long cycle) {
            int lo = 0, hi = n - 1, r = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (at[mid] <= cycle) { r = mid; lo = mid + 1; } else hi = mid - 1;
            }
            return r;
        }

        void truncateAfter(long cycle) {
            while (n > 0 && at[n - 1] > cycle) n--;
            if (bv != null) Arrays.fill(bv, n, bv.length, null);
        }

        /** Drop the changes before {@code cycle}, keeping the value in force at {@code cycle} (as its first change). */
        void dropBefore(long cycle) {
            int i = floor(cycle);
            if (i <= 0) return;
            System.arraycopy(at, i, at, 0, n - i);
            if (lv != null) System.arraycopy(lv, i, lv, 0, n - i);
            else { System.arraycopy(bv, i, bv, 0, n - i); Arrays.fill(bv, n - i, n, null); }
            n -= i;
            at[0] = Math.max(at[0], cycle);
        }

        /** Forget everything, and the memory it took. */
        void reset() {
            n = 0;
            at = new long[16];
            if (lv != null) lv = new long[16]; else bv = new BigInteger[16];
        }
    }

    private final Map<String, Track> tracks = new LinkedHashMap<>();
    private Map<String, Integer> widths = new LinkedHashMap<>();
    private final List<Map<String, Object>> marks = new ArrayList<>();

    /** A new design: keep the record of every signal that still exists with the same width; record {@code recorded} from now on. */
    void retarget(Map<String, Integer> signals, Set<String> recorded, long cycle) {
        widths = new LinkedHashMap<>(signals);
        tracks.keySet().removeIf(p -> !signals.containsKey(p) || signals.get(p) != tracks.get(p).width);
        for (var p : recorded) record(p, cycle);
    }

    void record(String path, long cycle) {
        Integer w = widths.get(path);
        if (w != null) tracks.computeIfAbsent(path, p -> new Track(w));
    }

    Set<String> paths() { return tracks.keySet(); }

    Map<String, Integer> widths() { return widths; }

    void sample(String path, long cycle, BigInteger v) {
        var t = tracks.get(path);
        if (t != null) t.add(cycle, v);
    }

    /** A new simulation from cycle 0: nothing recorded so far belongs to it (and its memory is given back). */
    void clear() {
        for (var t : tracks.values()) t.reset();
        marks.clear();
        dropped = -1;
    }

    // ---------------------------------------------------------------- a bounded record

    /** At most this many changes are kept in all; beyond it the oldest half of the record is dropped. */
    static final long BUDGET = Long.getLong("studio.waveBudget", 16_000_000L);
    long budget = BUDGET;          // per record (a test may lower it)
    private long dropped = -1;     // the record starts here (after dropping), or -1

    long size() { long s = 0; for (var t : tracks.values()) s += t.n; return s; }

    /** The first cycle still in the record (after any drop), or -1 when nothing was dropped. */
    long droppedBefore() { return dropped; }

    /** Keep the record within its budget: drop the changes before the middle of the recorded time, until it fits. */
    boolean trim(long now) {
        if (size() <= budget) return false;
        long first = Long.MAX_VALUE;
        for (var t : tracks.values()) if (t.n > 0) first = Math.min(first, t.at[0]);
        while (size() > budget * 6 / 10 && first < now) {
            long cut = first + (now - first) / 2;
            for (var t : tracks.values()) t.dropBefore(cut);
            first = cut;
        }
        dropped = first;
        final long from = first;
        marks.removeIf(m -> ((Number) m.get("cycle")).longValue() < from);
        marks.add(0, Map.of("cycle", from, "text", "the record before this was dropped to keep within memory (" + String.format("%,d", budget) + " changes)", "kind", "reload"));
        return true;
    }

    /** Leaving the past by changing an input: forget everything after {@code cycle}. */
    void truncate(long cycle) {
        for (var t : tracks.values()) t.truncateAfter(cycle);
        marks.removeIf(m -> ((Number) m.get("cycle")).longValue() > cycle);
        marks.add(Map.of("cycle", cycle, "text", "a new run starts here (an input changed in the past)", "kind", "fork"));
    }

    void markKind(long cycle, String text, String kind) {
        marks.add(Map.of("cycle", cycle, "text", text, "kind", kind));
    }

    void mark(long cycle, String text) {
        marks.add(Map.of("cycle", cycle, "text", text, "kind", "reload"));
    }

    List<Map<String, Object>> marks() { return marks; }

    /** Changes of {@code path} in (from, to] (the first sample of a track is its start, not a change), or -1 when it is not recorded. */
    int changes(String path, long from, long to) {
        var t = tracks.get(path);
        if (t == null) return -1;
        return Math.max(0, t.floor(to) - Math.max(t.floor(from), 0));
    }

    /**
     * Changes of {@code path} in [from, to]: {@code {width, exact, changes: [[cycle, hex], ...]}} with
     * the value at {@code from} first, or, when dense, {@code {exact: false, columns: [[cycle,
     * firstHex, changes, minHex, maxHex], ...]}}.
     */
    Map<String, Object> window(String path, long from, long to, int columns) {
        var m = new LinkedHashMap<String, Object>();
        var t = tracks.get(path);
        if (t == null) { m.put("missing", true); return m; }
        m.put("width", t.width);
        int i0 = Math.max(t.floor(from), 0), i1 = t.floor(to);
        int count = i1 - i0 + 1;
        columns = Math.max(1, columns);
        if (t.n == 0) { m.put("exact", true); m.put("changes", List.of()); return m; }
        if (count <= columns * 2L) {
            var ch = new ArrayList<Object>();
            for (int i = i0; i <= i1; i++) ch.add(List.of(t.at[i], t.hex(i)));
            m.put("exact", true);
            m.put("changes", ch);
            return m;
        }
        var cols = new ArrayList<Object>();
        double span = (double) (to - from + 1) / columns;
        int i = i0;
        for (int c = 0; c < columns; c++) {
            long cs = from + (long) Math.floor(c * span), ce = from + (long) Math.floor((c + 1) * span) - 1;
            if (ce < cs) ce = cs;
            while (i + 1 <= i1 && t.at[i + 1] <= cs) i++;
            int first = i, lo = i, hi = i;
            int changes = 0;
            int j = i;
            while (j + 1 <= i1 && t.at[j + 1] <= ce) {
                j++;
                changes++;
                if (t.cmp(j, lo) < 0) lo = j;
                if (t.cmp(j, hi) > 0) hi = j;
            }
            cols.add(List.of(cs, t.hex(first), changes, t.hex(lo), t.hex(hi)));
            i = j;
        }
        m.put("exact", false);
        m.put("columns", cols);
        return m;
    }

    /**
     * The first cycle after {@code from} (dir 1) or the last before it (dir -1), up to {@code head}, at which
     * {@code c} holds; null when there is none in the record. A condition can only start to hold where one of its
     * signals changes, so only those cycles are tried.
     */
    Long find(Cond c, long from, int dir, long head) {
        var points = new java.util.TreeSet<Long>();
        for (String p : c.signals()) {
            var t = tracks.get(p);
            if (t == null) throw new IllegalArgumentException(p + " is not recorded: add it to the waves (it is recorded from then on).");
            for (int i = 0; i < t.n; i++) if (t.at[i] <= head) points.add(t.at[i]);
        }
        var seq = dir > 0 ? points.tailSet(from, false) : points.headSet(from, false).descendingSet();
        for (long cy : seq) if (c.holds(q -> at(q, cy))) return cy;
        return null;
    }

    /** The value of {@code path} at {@code cycle}, or null. */
    BigInteger at(String path, long cycle) {
        var t = tracks.get(path);
        if (t == null) return null;
        int i = t.floor(cycle);
        return i < 0 ? null : t.get(i);
    }
}
