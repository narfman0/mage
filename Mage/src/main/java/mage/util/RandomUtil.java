package mage.util;

import java.awt.*;
import java.util.Collection;
import java.util.Random;

/**
 * The engine's random numbers: shuffles, the toss, coin flips, random choices.
 * <p>
 * One generator is shared by the process; a game that asked for a seed
 * ({@code GameOptions.gameSeed}) gets a generator of its own, bound to the
 * thread it runs on ({@link #bindThread}) at the moment it is seeded, so games
 * running at the same time in one JVM never interleave their draws and each
 * replays from its own seed. Every caller stays the same: the static methods
 * draw from the current thread's generator if one is bound, else the shared
 * one. A thread that hosts games one after another unbinds between them
 * ({@link #unbindThread}).
 * <p>
 * Created by IGOUDT on 5-9-2016.
 */
public final class RandomUtil {

    private static final Random shared = new Random(); // thread safe with seed support
    private static final ThreadLocal<Random> bound = new ThreadLocal<>();

    private RandomUtil() {
    }

    private static Random current() {
        Random r = bound.get();
        return r != null ? r : shared;
    }

    public static Random getRandom() {
        return current();
    }

    public static int nextInt() {
        return current().nextInt();
    }

    public static int nextInt(int max) {
        return current().nextInt(max);
    }

    public static boolean nextBoolean() {
        return current().nextBoolean();
    }

    public static double nextDouble() {
        return current().nextDouble();
    }

    public static Color nextColor() {
        return new Color(RandomUtil.nextInt(256), RandomUtil.nextInt(256), RandomUtil.nextInt(256));
    }

    /** Reseeds the current thread's generator: the game's own if one is bound, else the shared one. */
    public static void setSeed(long newSeed) {
        current().setSeed(newSeed);
    }

    /**
     * Gives the calling thread a generator of its own, seeded with {@code seed}.
     * Called by a seeded game on its game thread right before its first shuffle.
     */
    public static void bindThread(long seed) {
        bound.set(new Random(seed));
    }

    /** Back to the shared generator on this thread (a game host reusing a thread). */
    public static void unbindThread() {
        bound.remove();
    }

    /** Whether this thread draws from a generator of its own. */
    public static boolean isThreadBound() {
        return bound.get() != null;
    }

    public static <T> T randomFromCollection(Collection<T> collection) {
        if (collection.size() < 2) {
            return collection.stream().findFirst().orElse(null);
        }
        int rand = nextInt(collection.size());
        int count = 0;
        for (T current : collection) {
            if (count == rand) {
                return current;
            }
            count++;
        }
        return null;
    }
}
