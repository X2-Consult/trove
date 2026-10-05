package org.booklore.service.metadata.parser;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.enums.MetadataProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Request pacing and anti-bot block tracking for the scraped metadata sources (Amazon, GoodReads).
 *
 * <p>Pacing is shared across threads, so a bulk refresh, a manual search and a bookdrop import
 * running at the same time still space their requests to one site instead of bursting it.
 *
 * <p>When a parser sees an anti-bot response it calls {@link #markBlocked}. Until the cooldown
 * expires {@link #isBlocked} is true and the parser skips the network rather than feeding the
 * block; the first request after the cooldown is the probe that finds out whether it cleared.
 * A block can be tied to a credential (the user's Amazon cookie) so that pasting a fresh
 * cookie takes effect immediately instead of waiting out the cooldown.
 */
@Slf4j
@Component
public class MetadataProviderGuard {

    record Pacing(Duration minGap, Duration jitter) {}

    static final Map<MetadataProvider, Pacing> PACING = Map.of(
            MetadataProvider.Amazon, new Pacing(Duration.ofMillis(1500), Duration.ofMillis(1000)),
            MetadataProvider.GoodReads, new Pacing(Duration.ofMillis(1000), Duration.ofMillis(1000)),
            // Apple's iTunes Search API allows about 20 requests a minute.
            MetadataProvider.AppleBooks, new Pacing(Duration.ofMillis(3000), Duration.ofMillis(500)));

    // GoodReads' WAF gate comes and goes within minutes; Amazon's bot check tends to stick
    // (30 min is what the Bookshelf backend's Amazon scraper backs off for).
    // Google's 429 without an API key usually means the shared per-IP daily quota is gone.
    static final Map<MetadataProvider, Duration> BLOCK_COOLDOWN = Map.of(
            MetadataProvider.Amazon, Duration.ofMinutes(30),
            MetadataProvider.GoodReads, Duration.ofMinutes(3),
            MetadataProvider.Google, Duration.ofMinutes(30),
            MetadataProvider.AppleBooks, Duration.ofMinutes(2));

    private static final Duration DEFAULT_BLOCK_COOLDOWN = Duration.ofMinutes(5);

    @FunctionalInterface
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private record Block(long untilNanos, String credential) {}

    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final Map<MetadataProvider, Long> nextSlotNanos = new ConcurrentHashMap<>();
    private final Map<MetadataProvider, Block> blocks = new ConcurrentHashMap<>();

    public MetadataProviderGuard() {
        this(System::nanoTime, nanos -> Thread.sleep(Duration.ofNanos(nanos)));
    }

    MetadataProviderGuard(LongSupplier nanoClock, Sleeper sleeper) {
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
    }

    /**
     * Blocks until this provider's next request slot. Slots are reserved under a lock but the
     * wait happens outside it, so concurrent callers queue up in order without holding the lock.
     */
    public void awaitTurn(MetadataProvider provider) {
        Pacing pacing = PACING.get(provider);
        if (pacing == null) {
            return;
        }
        long waitNanos;
        synchronized (nextSlotNanos) {
            long now = nanoClock.getAsLong();
            long slot = Math.max(now, nextSlotNanos.getOrDefault(provider, now));
            long gap = pacing.minGap().toNanos() + ThreadLocalRandom.current().nextLong(pacing.jitter().toNanos() + 1);
            nextSlotNanos.put(provider, slot + gap);
            waitNanos = slot - now;
        }
        if (waitNanos > 0) {
            try {
                sleeper.sleepNanos(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    public void markBlocked(MetadataProvider provider) {
        markBlocked(provider, null);
    }

    public void markBlocked(MetadataProvider provider, String credential) {
        Duration cooldown = BLOCK_COOLDOWN.getOrDefault(provider, DEFAULT_BLOCK_COOLDOWN);
        blocks.put(provider, new Block(nanoClock.getAsLong() + cooldown.toNanos(), credential));
        log.warn("{}: anti-bot block detected, pausing requests for {} min", provider, cooldown.toMinutes());
    }

    /** A request got real content, so whatever block was recorded no longer applies. */
    public void clearBlock(MetadataProvider provider) {
        if (blocks.remove(provider) != null) {
            log.info("{}: requests are getting through again, anti-bot cooldown cleared", provider);
        }
    }

    /** Whether the provider is inside a block cooldown, regardless of which credential tripped it. */
    public boolean isBlocked(MetadataProvider provider) {
        Block block = blocks.get(provider);
        return block != null && nanoClock.getAsLong() - block.untilNanos() < 0;
    }

    /**
     * Whether the provider is blocked for this credential. A block recorded under a different
     * credential doesn't count, so a request with a newly configured cookie goes through.
     */
    public boolean isBlocked(MetadataProvider provider, String credential) {
        Block block = blocks.get(provider);
        return block != null
                && nanoClock.getAsLong() - block.untilNanos() < 0
                && Objects.equals(normalize(block.credential()), normalize(credential));
    }

    private static String normalize(String credential) {
        return credential == null || credential.isBlank() ? null : credential.strip();
    }
}
