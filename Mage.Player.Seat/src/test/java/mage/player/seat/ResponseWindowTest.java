package mage.player.seat;

import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.players.Player;
import mage.util.ThreadUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/**
 * The response window (HumanPlayer.prepareForResponse / waitForResponse):
 * the game thread opens it, fires the question, then sleeps on the response
 * object until the call thread's answer notifies it. The seat answers as soon
 * as the question is delivered — which is before the sleep — so a signal can
 * land in the gap. An answer with a value was already kept (the game thread
 * checked for one before sleeping); a cancel (a null UUID) and a rollback's
 * abort were not, and the game thread slept through their notify with nobody
 * left to wake it: the fork CI's "no decision within 120 s" stalls, once
 * every few runs on a loaded runner, backing out of an activation
 * (GameHostTest.activationCanBeBackedOutOf) and after an undo
 * (RollbackTest). Here the gap is held open on purpose and each signal sent
 * into it; the wait must come straight back.
 */
public class ResponseWindowTest {

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    /** The seat's player with the window's two halves callable from the test. */
    private static final class Probe extends SeatPlayer {
        Probe(SeatPlayer player) {
            super(player);
        }

        void open(Game game) {
            prepareForResponse(game);
        }

        void sleep(Game game) {
            waitForResponse(game);
        }
    }

    private static Probe you(GameHost host) {
        for (Player p : host.game().getPlayers().values()) {
            if ("You".equals(p.getName())) {
                return new Probe((SeatPlayer) p);
            }
        }
        throw new AssertionError("no seat named You");
    }

    /**
     * Opens the window on a game thread, runs {@code signal} on this thread
     * while the game thread is held between the open and the wait, then lets
     * it wait. True when the wait came back; false when it slept on.
     */
    private static boolean waitReturns(Game game, Probe you, Runnable signal) throws InterruptedException {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch signalled = new CountDownLatch(1);
        Thread gameThread = new Thread(() -> {
            you.open(game);
            opened.countDown();
            try {
                signalled.await();
            } catch (InterruptedException e) {
                return;
            }
            you.sleep(game);
        }, ThreadUtils.THREAD_PREFIX_GAME + " window test");
        gameThread.start();
        opened.await();
        signal.run();
        signalled.countDown();
        gameThread.join(5_000);
        boolean returned = !gameThread.isAlive();
        if (!returned) {
            gameThread.interrupt();
        }
        return returned;
    }

    private static GameHost host(String id) throws Exception {
        return new GameHost(new GameHost.Config(id, "duel", 1L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", GameHostTest.BEARS, 6)), false));
    }

    @Test(timeout = 60_000)
    public void aCancelThatLandsBeforeTheWaitIsNotSleptThrough() throws Exception {
        GameHost host = host("window-cancel");
        Probe you = you(host);
        Assert.assertTrue("the wait came back for a cancel (null UUID) sent before it",
                waitReturns(host.game(), you, () -> you.setResponseUUID(null)));
    }

    @Test(timeout = 60_000)
    public void anAbortThatLandsBeforeTheWaitIsNotSleptThrough() throws Exception {
        GameHost host = host("window-abort");
        Probe you = you(host);
        Assert.assertTrue("the wait came back for a rollback's abort sent before it",
                waitReturns(host.game(), you, you::abort));
    }

    @Test(timeout = 60_000)
    public void anAnswerThatLandsBeforeTheWaitIsKept() throws Exception {
        GameHost host = host("window-answer");
        Probe you = you(host);
        UUID answer = UUID.randomUUID();
        Assert.assertTrue("the wait came back for an answer sent before it",
                waitReturns(host.game(), you, () -> you.setResponseUUID(answer)));
    }
}
