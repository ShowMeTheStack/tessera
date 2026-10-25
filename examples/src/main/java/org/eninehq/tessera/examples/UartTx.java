package org.eninehq.tessera.examples;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/**
 * An 8N1 UART transmitter. Present a byte on {@code data} with {@code valid}; it is taken
 * when {@code ready} is high. Each bit lasts {@code clocksPerBit} cycles.
 */
public class UartTx extends Module {
    public final UInt data = input("data", UInt.w(8));
    public final Bool valid = input("valid", Bool.TYPE);
    public final Bool ready = output("ready", Bool.TYPE);
    public final Bool tx = output("tx", Bool.TYPE);

    public UartTx(int clocksPerBit) {
        int timerBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(clocksPerBit - 1));
        UInt shift = reg("shift", UInt.w(10), 0x3FF);   // {stop, data[7:0], start}, sent LSB first
        UInt bitsLeft = reg("bitsLeft", UInt.w(4), 0);
        UInt timer = reg("timer", UInt.w(timerBits), 0);

        Bool idle = bitsLeft.eq(0).named("idle");
        ready.set(idle);
        tx.set(shift.bit(0));

        when(idle, () -> when(valid, () -> {
            shift.set(UInt.lit(1, 1).cat(data, UInt.lit(0, 1)));
            bitsLeft.set(10);
            timer.set(clocksPerBit - 1);
        })).otherwise(() -> when(timer.eq(0), () -> {
            shift.set(UInt.lit(1, 1).cat(shift.bits(9, 1)));
            bitsLeft.set(bitsLeft.subWrap(1));
            timer.set(clocksPerBit - 1);
        }).otherwise(() -> timer.set(timer.subWrap(1))));
    }
}
