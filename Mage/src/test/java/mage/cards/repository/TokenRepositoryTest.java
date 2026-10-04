package mage.cards.repository;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The repository fills itself on first use, and the first use can be several
 * game threads at once (every game's init asks for the helper emblem's image):
 * each of them must see the whole list, never one another thread is still
 * filling.
 */
public class TokenRepositoryTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 20;

    @Test
    public void firstUseFromManyThreadsSeesTheWholeList() throws Exception {
        int expected = TokenRepository.instance.getByType(TokenType.XMAGE).size();
        Assert.assertTrue("xmage tokens loaded: " + expected, expected > 0);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                forget();
                CountDownLatch go = new CountDownLatch(1);
                List<Future<TokenInfo>> asked = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    asked.add(pool.submit(() -> {
                        go.await();
                        // what GameImpl.initGameDefaultHelperEmblems asks on every game's start
                        return TokenRepository.instance.findPreferredTokenInfoForXmage(
                                TokenRepository.XMAGE_IMAGE_NAME_HELPER_EMBLEM, null);
                    }));
                }
                go.countDown();
                for (Future<TokenInfo> answer : asked) {
                    Assert.assertNotNull("round " + round + ": the helper emblem's image", answer.get());
                }
                Assert.assertEquals("round " + round + ": xmage tokens",
                        expected, TokenRepository.instance.getByType(TokenType.XMAGE).size());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Puts the repository back to how a fresh JVM has it. */
    private static void forget() throws Exception {
        synchronized (TokenRepository.instance) {
            field("allTokens").set(TokenRepository.instance, new ArrayList<TokenInfo>());
            ((Map<?, ?>) field("indexByClassName").get(TokenRepository.instance)).clear();
            ((Map<?, ?>) field("indexByType").get(TokenRepository.instance)).clear();
        }
    }

    private static Field field(String name) throws Exception {
        Field f = TokenRepository.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
