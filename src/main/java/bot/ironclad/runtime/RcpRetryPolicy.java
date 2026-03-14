package bot.ironclad.runtime;

import io.smallrye.mutiny.CompositeException;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class RcpRetryPolicy {
    private final int maxAttempts;
    private final RetryDelayStrategy delayStrategy;
    private final Predicate<Throwable> retryPredicate;

    private RcpRetryPolicy(int maxAttempts, RetryDelayStrategy delayStrategy, Predicate<Throwable> retryPredicate) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }

        this.maxAttempts = maxAttempts;
        this.delayStrategy = Objects.requireNonNull(delayStrategy, "delayStrategy");
        this.retryPredicate = Objects.requireNonNull(retryPredicate, "retryPredicate");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RcpRetryPolicy noDelay(int maxAttempts, Predicate<Throwable> retryPredicate) {
        return builder()
                .maxAttempts(maxAttempts)
                .retryPredicate(retryPredicate)
                .build();
    }

    public static RcpRetryPolicy timeoutOnly(int maxAttempts) {
        return noDelay(
                maxAttempts,
                failure -> failure instanceof java.util.concurrent.TimeoutException
                        || failure instanceof io.smallrye.mutiny.TimeoutException
        );
    }

    public static RcpRetryPolicy fixedDelay(int maxAttempts, Duration delay, Predicate<Throwable> retryPredicate) {
        return builder()
                .maxAttempts(maxAttempts)
                .delayStrategy((attempt, failure) -> delay)
                .retryPredicate(retryPredicate)
                .build();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    boolean shouldRetry(int attempt, Throwable failure) {
        return attempt < maxAttempts && matchesRetryableFailure(failure, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    Duration delayBeforeRetry(int attempt, Throwable failure) {
        var delay = Objects.requireNonNull(delayStrategy.delayBeforeRetry(attempt, failure), "delayStrategy result");
        if (delay.isNegative()) {
            throw new IllegalStateException("Retry delay must not be negative");
        }
        return delay;
    }

    @FunctionalInterface
    public interface RetryDelayStrategy {
        Duration delayBeforeRetry(int attempt, Throwable failure);
    }

    private boolean matchesRetryableFailure(Throwable failure, Set<Throwable> seenFailures) {
        if (failure == null || !seenFailures.add(failure)) {
            return false;
        }

        if (retryPredicate.test(failure)) {
            return true;
        }

        if (failure instanceof CompositeException compositeException) {
            for (var cause : compositeException.getCauses()) {
                if (matchesRetryableFailure(cause, seenFailures)) {
                    return true;
                }
            }
        }

        return matchesRetryableFailure(failure.getCause(), seenFailures);
    }

    public static final class Builder {
        private int maxAttempts = 1;
        private RetryDelayStrategy delayStrategy = (attempt, failure) -> Duration.ZERO;
        private Predicate<Throwable> retryPredicate = failure -> false;

        private Builder() {
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder delayStrategy(RetryDelayStrategy delayStrategy) {
            this.delayStrategy = Objects.requireNonNull(delayStrategy, "delayStrategy");
            return this;
        }

        public Builder retryPredicate(Predicate<Throwable> retryPredicate) {
            this.retryPredicate = Objects.requireNonNull(retryPredicate, "retryPredicate");
            return this;
        }

        public RcpRetryPolicy build() {
            return new RcpRetryPolicy(maxAttempts, delayStrategy, retryPredicate);
        }
    }
}
