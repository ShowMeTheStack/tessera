package org.eninehq.tessera.studio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Waves as FST, GTKWave's compressed format (also read by Surfer and Verilator's tools). The layout is
 * the one fstapi.c writes: a header block, one value-change block (each signal's changes as a chain of
 * time-index deltas, two-state values packed in bits), a geometry block (the widths) and a gzip
 * hierarchy block (one scope, one wire per signal). Values are two-state, as the simulator's are; chains
 * are stored uncompressed, which fstapi allows and its reader accepts.
 */
final class FstWriter {
    /** A signal: its name in the scope, its width, and its changes in time order (time, value). */
    record Var(String name, int width, List<long[]> times, List<BigInteger> values) {}

    private FstWriter() {}

    /**
     * The FST bytes of {@code vars} in a scope named {@code scope}; times are in units of
     * 10^{@code exponent} seconds (-9 is nanoseconds).
     */
    static byte[] write(String scope, List<Var> vars, int exponent) {
        // the time table: every time at which something changes, in order
        var all = new java.util.TreeSet<Long>();
        for (var v : vars) for (var t : v.times()) all.add(t[0]);
        if (all.isEmpty()) all.add(0L);
        long[] times = all.stream().mapToLong(Long::longValue).toArray();
        var index = new java.util.HashMap<Long, Integer>();
        for (int i = 0; i < times.length; i++) index.put(times[i], i);
        long first = times[0], last = times[times.length - 1];

        var out = new Buf();
        // ---- header (330 bytes); counts are known up front, so nothing is patched afterwards
        out.u8(0);
        out.u64(329);
        out.u64(first);
        out.u64(last);
        out.f64le(2.7182818284590452354);   // the endian test, in the writer's (little-endian) order
        out.u64(0);                          // memory the writer used
        out.u64(1);                          // scopes
        out.u64(vars.size());                // variables
        out.u64(vars.size());                // the largest handle
        out.u64(1);                          // value-change sections
        out.u8(exponent & 255);
        out.fixed("Tessera Studio", 128);
        out.fixed(new java.util.Date().toString() + "\n", 119);
        out.u8(0);                           // file type: Verilog
        out.u64(0);                          // time zero

        // ---- the value-change block
        int maxvalpos = 0;
        for (var v : vars) maxvalpos += v.width();
        var block = new Buf();
        block.u64(first);                   // begin time
        block.u64(last);                    // end time
        int memAt = block.size();
        block.u64(0);                        // reader memory for the chains (patched below)
        // values at the section's start: unknown until the first change, as fstapi writes them
        block.varint(maxvalpos);
        block.varint(maxvalpos);             // the same length: not compressed
        block.varint(vars.size());
        for (var v : vars) for (int i = 0; i < v.width(); i++) block.u8('x');
        // the chains
        block.varint(vars.size());
        block.u8('Z');
        long fpos = 1, memreq = 0;
        long[] pos = new long[vars.size()];
        for (int h = 0; h < vars.size(); h++) {
            var v = vars.get(h);
            if (v.times().isEmpty()) continue;
            var chain = new Buf();
            int prev = 0;
            for (int k = 0; k < v.times().size(); k++) {
                int idx = index.get(v.times().get(k)[0]);
                int delta = idx - prev;
                prev = idx;
                BigInteger val = v.values().get(k);
                if (v.width() == 1) chain.varint(((long) (val.testBit(0) ? 1 : 0) << 1) | ((long) delta << 2));
                else {
                    chain.varint((long) delta << 1);
                    // the bits most significant first, eight to a byte, the last byte padded at the low end
                    int nbytes = (v.width() + 7) / 8;
                    for (int b = 0; b < nbytes; b++) {
                        int acc = 0;
                        for (int j = 0; j < 8; j++) {
                            int bit = b * 8 + j;       // 0 is the most significant
                            if (bit < v.width() && val.testBit(v.width() - 1 - bit)) acc |= 0x80 >> j;
                        }
                        chain.u8(acc);
                    }
                }
            }
            pos[h] = fpos;
            byte[] c = chain.bytes();
            fpos += block.varint(0);         // 0: the chain follows uncompressed
            block.raw(c);
            fpos += c.length;
            memreq += c.length;
        }
        // the index: where each signal's chain is (deltas), runs of signals without changes as counts
        int indexAt = block.size();
        long prevpos = 0;
        int zerocnt = 0;
        for (int h = 0; h < vars.size(); h++) {
            if (pos[h] != 0) {
                if (zerocnt > 0) { block.varint((long) zerocnt << 1); zerocnt = 0; }
                block.svarint(((pos[h] - prevpos) << 1) | 1);
                prevpos = pos[h];
            } else zerocnt++;
        }
        if (zerocnt > 0) block.varint((long) zerocnt << 1);
        block.u64(block.size() - indexAt);
        // the time table, uncompressed (its two lengths equal)
        var tt = new Buf();
        long cur = 0;
        for (long t : times) { tt.varint(t - cur); cur = t; }
        byte[] tb = tt.bytes();
        block.raw(tb);
        block.u64(tb.length);
        block.u64(tb.length);
        block.u64(times.length);
        byte[] bb = block.bytes();
        ByteBuffer.wrap(bb, memAt, 8).order(ByteOrder.BIG_ENDIAN).putLong(memreq);
        out.u8(8);                           // FST_BL_VCDATA_DYN_ALIAS2
        out.u64(bb.length + 8L);             // the length counts itself
        out.raw(bb);

        // ---- geometry: each signal's width
        var geo = new Buf();
        for (var v : vars) geo.varint(v.width());
        byte[] gb = geo.bytes();
        out.u8(3);
        out.u64(gb.length + 24L);
        out.u64(gb.length);
        out.u64(vars.size());
        out.raw(gb);

        // ---- hierarchy, gzip
        var hier = new Buf();
        hier.u8(254); hier.u8(0); hier.str(scope); hier.str("");   // a module scope
        for (var v : vars) {
            hier.u8(16); hier.u8(0); hier.str(v.name());            // a wire, direction implicit
            hier.varint(v.width());
            hier.varint(0);                                          // not an alias
        }
        hier.u8(255);                                                // upscope
        byte[] hb = hier.bytes();
        var gz = new ByteArrayOutputStream();
        try (var z = new GZIPOutputStream(gz)) { z.write(hb); } catch (IOException e) { throw new UncheckedIOException(e); }
        byte[] zb = gz.toByteArray();
        out.u8(4);
        out.u64(zb.length + 16L);
        out.u64(hb.length);
        out.raw(zb);
        return out.bytes();
    }

    /** A byte buffer with FST's integers: big-endian 64-bit words and LEB128 varints. */
    private static final class Buf {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream();

        int size() { return b.size(); }
        byte[] bytes() { return b.toByteArray(); }
        void u8(int v) { b.write(v); }
        void raw(byte[] x) { b.writeBytes(x); }

        void u64(long v) { for (int i = 7; i >= 0; i--) b.write((int) (v >>> (8 * i)) & 255); }

        void f64le(double d) {
            long v = Double.doubleToLongBits(d);
            for (int i = 0; i < 8; i++) b.write((int) (v >>> (8 * i)) & 255);
        }

        int varint(long v) {
            int n = 0;
            while ((v >>> 7) != 0) { b.write((int) (v & 0x7f) | 0x80); v >>>= 7; n++; }
            b.write((int) v);
            return n + 1;
        }

        void svarint(long v) {
            while (true) {
                int byt = (int) (v & 0x7f);
                v >>= 7;
                boolean done = (v == 0 && (byt & 0x40) == 0) || (v == -1 && (byt & 0x40) != 0);
                b.write(done ? byt : byt | 0x80);
                if (done) return;
            }
        }

        void str(String s) { b.writeBytes(s.getBytes(StandardCharsets.UTF_8)); b.write(0); }

        void fixed(String s, int n) {
            byte[] x = s.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < n; i++) b.write(i < x.length && i < n - 1 ? x[i] : 0);
        }
    }
}
