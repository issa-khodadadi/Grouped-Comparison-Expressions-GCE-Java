package dev.gce.runtime;

import java.util.function.DoublePredicate;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * Retained runtime from version 0.2.1. Version 1.0.0 does not emit calls to this class.
 *
 * <p>A group is lowered to {@code GceEval.of(subject).is(temp -> predicate)}.
 * {@code of} is called from the enclosing expression, so the subject is an argument:
 * it is not captured by the predicate lambda, a checked exception it throws can
 * propagate, and {@code false && of(subject).is(...)} does not evaluate {@code subject}.
 * The predicate runs once, inside {@code is}, so {@code &&} and {@code ||} in the
 * group still short-circuit and each operand runs only when that comparison is reached.
 *
 * <p>Overload resolution sees only {@code of}'s value parameter. The lambda is not
 * part of that overload, which is what keeps {@code int} and {@code Integer} apart:
 * a primitive selects a primitive chain, and a reference selects {@link RefChain}.
 * {@code char}, {@code byte}, and {@code short} widen to {@code int}. {@code float}
 * widens to {@code double}, which can represent every {@code float} exactly.
 * A {@code null} literal selects {@link RefChain}.
 */
public final class GceEval {
    private GceEval() {}

    /** Functional interface for a primitive {@code boolean} subject. Not {@link Predicate}. */
    @FunctionalInterface
    public interface BooleanPredicate {
        boolean test(boolean value);
    }

    public static BooleanChain of(boolean subject) {
        return new BooleanChain(subject);
    }

    public static IntChain of(int subject) {
        return new IntChain(subject);
    }

    public static LongChain of(long subject) {
        return new LongChain(subject);
    }

    public static DoubleChain of(double subject) {
        return new DoubleChain(subject);
    }

    public static <T> RefChain<T> of(T subject) {
        return new RefChain<>(subject);
    }

    public static final class BooleanChain {
        private final boolean value;

        BooleanChain(boolean value) {
            this.value = value;
        }

        public boolean is(BooleanPredicate predicate) {
            return predicate.test(value);
        }
    }

    public static final class IntChain {
        private final int value;

        IntChain(int value) {
            this.value = value;
        }

        public boolean is(IntPredicate predicate) {
            return predicate.test(value);
        }
    }

    public static final class LongChain {
        private final long value;

        LongChain(long value) {
            this.value = value;
        }

        public boolean is(LongPredicate predicate) {
            return predicate.test(value);
        }
    }

    public static final class DoubleChain {
        private final double value;

        DoubleChain(double value) {
            this.value = value;
        }

        public boolean is(DoublePredicate predicate) {
            return predicate.test(value);
        }
    }

    /** Reference subjects, including boxed primitives. {@code ==} stays identity. */
    public static final class RefChain<T> {
        private final T value;

        RefChain(T value) {
            this.value = value;
        }

        public boolean is(Predicate<T> predicate) {
            return predicate.test(value);
        }
    }
}
