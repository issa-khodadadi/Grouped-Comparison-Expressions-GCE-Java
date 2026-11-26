// SPDX-License-Identifier: Apache-2.0
package dev.gce.runtime;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Optional helper for callers who already have a supplier and a predicate.
 *
 * <p><strong>The translator does not emit calls to this class.</strong>
 * Generated groups call {@link GceEval#of}. {@code test} boxes a primitive subject
 * because {@code T} is a reference type. After that boxing, {@code ==} and {@code !=}
 * are reference comparisons, so two {@code int} values of {@code 1000} can compare
 * unequal. Do not use {@code test} as the meaning of a grouped comparison.
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
