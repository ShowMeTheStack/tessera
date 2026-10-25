package org.eninehq.tessera.riscv;

/** One retired instruction, for comparing a core against the golden model. */
public record Retire(int pc, int insn, int rd, int rdValue) {
    @Override
    public String toString() {
        return String.format("pc=%08x insn=%08x x%d=%08x", pc, insn, rd, rdValue);
    }
}
