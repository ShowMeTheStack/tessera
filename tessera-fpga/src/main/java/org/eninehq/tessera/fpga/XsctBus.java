package org.eninehq.tessera.fpga;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The SoC on a Zynq board through JTAG: an interactive xsct (Vivado 2020.1, in docker on the
 * machine the board is plugged into, reached over ssh) programs the FPGA, initialises the PS
 * and then reads and writes the SoC's AXI-Lite registers through the ARM's debug port. No
 * software runs on the ARM.
 */
public final class XsctBus implements SocBus {
    public static final long BASE = 0x43C0_0000L;

    private final Process proc;
    private final PrintWriter in;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> log = new ArrayList<>();
    private int seq;

    /**
     * @param host ssh destination ({@code user@zed-host})
     * @param dir  directory on the host with soc.bit, soc.xsa and ps7_init.tcl
     */
    public XsctBus(String host, String dir, String image) {
        String docker = "sudo docker run --rm -i --privileged --net=host -v /dev/bus/usb:/dev/bus/usb"
                + " -v $HOME/tessera-zed:/z -v $PWD:/w -w /w " + image
                + " bash -c 'source /opt/Xilinx/Vivado/2020.1/settings64.sh; export DISPLAY=:99;"
                + " export PATH=/z/fakebin:/opt/Xilinx/Vitis/2020.1/bin:$PATH; exec xsct -interactive'";
        try {
            proc = new ProcessBuilder("ssh", "-o", "BatchMode=yes", host, "cd " + dir + " && " + docker)
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        in = new PrintWriter(proc.getOutputStream(), true, StandardCharsets.UTF_8);
        var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(() -> {
            try {
                for (String l; (l = reader.readLine()) != null; ) lines.add(l);
            } catch (IOException ignored) {
            }
            lines.add("@@EOF");
        });
    }

    /** Program the FPGA with soc.bit and bring up the PS (clocks, PS-PL bridges) with ps7_init. */
    public static XsctBus open(String host, String dir) {
        return open(host, dir, 1);
    }

    /**
     * As {@link #open(String, String)}, then divide the fabric clock FCLK_CLK0 by {@code slowdown}
     * (multiplying DIVISOR1 of FPGA0_CLK_CTRL): for a bitstream that meets timing only below the
     * frequency ps7_init sets.
     */
    public static XsctBus open(String host, String dir, int slowdown) {
        return open(host, dir, slowdown, true);
    }

    /** With {@code program} false the FPGA keeps the design it has (flashed by openFPGALoader, say). */
    public static XsctBus open(String host, String dir, int slowdown, boolean program) {
        var bus = new XsctBus(host, dir, "kkrizka/vivado:2020.1");
        bus.tcl("connect");
        if (program) {
            bus.tcl("targets -set -nocase -filter {name =~ \"APU*\"}");
            bus.tcl("rst -system");
            bus.tcl("after 1000");
            bus.tcl("targets -set -nocase -filter {name =~ \"xc7z020*\"}");
            bus.tcl("fpga -file soc.bit");
        }
        bus.tcl("targets -set -nocase -filter {name =~ \"APU*\"}");
        bus.tcl("source ps7_init.tcl");
        bus.tcl("ps7_init");
        bus.tcl("ps7_post_config");
        if (slowdown > 1) {
            bus.tcl("mwr 0xF8000008 0xDF0D");                                  // SLCR unlock
            long v = Long.decode(bus.tcl("mrd -value 0xF8000170"));
            long div1 = (v >> 20) & 0x3F;
            long nv = (v & ~(0x3FL << 20)) | ((div1 * slowdown) & 0x3F) << 20;
            bus.tcl("mwr 0xF8000170 " + hex(nv));
            bus.tcl("mwr 0xF8000004 0x767B");                                  // SLCR lock
        }
        bus.tcl("targets -set -nocase -filter {name =~ \"*A9*#0\"}");
        bus.tcl("catch {stop}");
        // loadhw unpacks the .xsa (which contains a Vivado soc.bit) next to it: keep it in hw/ so that it does
        // not overwrite the soc.bit of an openXC7 build in this directory
        bus.tcl("file mkdir hw; file copy -force soc.xsa hw/soc.xsa");
        bus.tcl("loadhw -hw hw/soc.xsa -mem-ranges [list {0x40000000 0xbfffffff}]");
        return bus;
    }

    /** Run one Tcl command; returns its result (throws with xsct's message on error). */
    public synchronized String tcl(String command) {
        int id = ++seq;
        in.println("if {[catch {" + command + "} r]} {puts \"@@" + id + "!$r\"} else {puts \"@@" + id + " $r\"}");
        String tag = "@@" + id;
        try {
            while (true) {
                String l = lines.poll(600, TimeUnit.SECONDS);
                if (l == null) throw new IllegalStateException("xsct timed out on: " + command);
                if (l.equals("@@EOF")) throw new IllegalStateException("xsct exited:\n" + String.join("\n", log));
                int at = l.indexOf(tag);
                if (at >= 0 && l.length() > at + tag.length()) {
                    char c = l.charAt(at + tag.length());
                    if (c == ' ') return l.substring(at + tag.length() + 1).trim();
                    if (c == '!') throw new IllegalStateException("xsct: " + command + ": " + l.substring(at + tag.length() + 1));
                }
                log.add(l);
                if (log.size() > 200) log.removeFirst();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String hex(long v) {
        return "0x" + Long.toHexString(v);
    }

    @Override
    public void write(long offset, long value) {
        tcl("mwr " + hex(BASE + offset) + " " + hex(value));
    }

    @Override
    public long read(long offset) {
        return Long.decode(tcl("mrd -value " + hex(BASE + offset)));
    }

    @Override
    public void writeBlock(long offset, int[] words) {
        for (int at = 0; at < words.length; at += 1024) {
            int n = Math.min(1024, words.length - at);
            var sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : " ").append(hex(Integer.toUnsignedLong(words[at + i])));
            tcl("mwr " + hex(BASE + offset + 4L * at) + " {" + sb + "} " + n);
        }
    }

    @Override
    public long[] readBlock(long offset, int n) {
        long[] out = new long[n];
        for (int at = 0; at < n; at += 1024) {
            int k = Math.min(1024, n - at);
            String[] v = tcl("mrd -value " + hex(BASE + offset + 4L * at) + " " + k).trim().split("\\s+");
            for (int i = 0; i < k; i++) out[at + i] = Long.decode(v[i]) & 0xFFFF_FFFFL;
        }
        return out;
    }

    @Override
    public boolean waitFor(long offset, long mask, int maxPolls) {
        String r = tcl("set n 0; while {([mrd -value " + hex(BASE + offset) + "] & " + mask + ") == 0 && $n < " + maxPolls
                + "} {incr n}; expr {$n < " + maxPolls + "}");
        return r.equals("1");
    }

    /**
     * The fabric clock as the board runs it: CYCLES read twice, {@code ms} milliseconds apart by
     * the host's clock, while the core spins (the caller starts a non-halting program). In MHz.
     */
    public double measureClockMHz(int ms) {
        String r = tcl("set a [mrd -value " + hex(BASE + SocHost.CYCLES) + "]; set t0 [clock microseconds]; after " + ms
                + "; set b [mrd -value " + hex(BASE + SocHost.CYCLES) + "]; set t1 [clock microseconds];"
                + " expr {double(($b - $a) & 0xFFFFFFFF) / ($t1 - $t0)}");
        return Double.parseDouble(r);
    }

    @Override
    public void close() {
        in.println("exit");
        in.close();
        try {
            if (!proc.waitFor(30, TimeUnit.SECONDS)) proc.destroy();
        } catch (InterruptedException e) {
            proc.destroy();
            Thread.currentThread().interrupt();
        }
    }
}
