package org.eninehq.tessera.notation;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** Tessera-javac notation: an 8N1 UART transmitter, 4 clocks per bit. */
public class UartTx extends Module {
    public UartTx() {
        UInt data = input("data", UInt.w(8));
        Bool valid = input("valid", Bool.TYPE);
        UInt shift = reg("shift", UInt.w(10), 0x3FF);
        UInt left = reg("left", UInt.w(4), 0);
        UInt timer = reg("timer", UInt.w(2), 0);
        Bool idle = left.eq(0);
        output("ready", Bool.TYPE).set(idle);
        output("tx", Bool.TYPE).set(shift[0]);
        when (idle) {
            when (valid) {
                shift.set({UInt.lit(1, 1), data, UInt.lit(0, 1)});
                left.set(10);
                timer.set(3);
            }
        }.elseWhen (timer.eq(0)) {
            shift.set({UInt.lit(1, 1), shift[9:1]});
            left.set(left.subWrap(1));
            timer.set(3);
        }.otherwise {
            timer.set(timer.subWrap(1));
        }
    }
}
