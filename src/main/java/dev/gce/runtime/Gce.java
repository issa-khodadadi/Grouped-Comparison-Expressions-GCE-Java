package dev.gce.runtime;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Optional runtime helper for callers who already have a supplier and a predicate.
 *
 * <p>The version 0.2 transpiler does <strong>not</strong> emit calls to this class.
 * A generic {@code Supplier}/{@code Predicate} pair boxes primitive subjects and can
 * change the meaning of {@code ==} and {@code !=}. Generated code instead evaluates the
 * subject into a {@code var} inside a {@code Supplier<Boolean>}, which keeps the original type.
 */
public final class Gce {
    private Gce() {}

    /**
     * Evaluates {@code subject} once and passes that value to {@code predicate}.
     * Short-circuit behavior inside {@code predicate} is ordinary Java behavior.
     * Primitive values are boxed because {@code T} is a reference type.
     */
    public static <T> boolean test(Supplier<T> subject, Predicate<T> predicate) {
        Objects.requireNonNull(subject, "subject supplier");
        Objects.requireNonNull(predicate, "predicate");
        T value = subject.get();
        return predicate.test(value);
    }
}
