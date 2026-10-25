package org.eninehq.tessera.pipeline;

/**
 * A named value that flows through an {@link InOrderPipeline}: a hardware value, or any Java
 * object made of hardware values (such as a record of decoded fields).
 */
public final class Key<T> {
    private final String name;

    private Key(String name) {
        this.name = name;
    }

    public static <T> Key<T> of(String name) {
        return new Key<>(name);
    }

    public String name() { return name; }

    @Override public String toString() { return name; }
}
