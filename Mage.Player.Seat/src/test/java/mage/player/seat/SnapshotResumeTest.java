package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Snapshot resume (Snapshot, GameHost's policy): a game against the CPU,
 * whose choices no record can replay, is written whole at every top-level
 * question and played on from that file in a fresh host — by the same seats,
 * or with the CPU's seat handed to a scripted seat.
 */
public class SnapshotResumeTest {

    private static final Logger LOG = Logger.getLogger(SnapshotResumeTest.class);
    private static final String BEARS = GameHostTest.BEARS;

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    private static GameHost.Config config(String id, Path logDir, String cpuKind, String snapshotFrom) {
        return new GameHost.Config(id, "duel", 7L, logDir.toString(),
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", cpuKind, BEARS, 6)),
                false, null, 0, 0, "host", snapshotFrom, true);
    }

    private static int turn(Map<String, Object> d) {
        String context = String.valueOf(d.get("context"));
        return Integer.parseInt(context.substring(1, context.indexOf(' ')));
    }

    /** Answers the seat's questions until the first one of {@code turn}, which is left open. */
    private static Map<String, Object> playTo(GameHost host, ScriptedSeat script, String seat, int turn) throws Exception {
        for (int i = 0; i < 400; i++) {
            Map<String, Object> d = host.awaitDecision(seat, 120_000);
            Assert.assertNull("engine error", d.get("error"));
            Assert.assertFalse("game over before turn " + turn + ": " + d, Boolean.TRUE.equals(d.get("game_over")));
            Assert.assertTrue("decision expected: " + d, Boolean.TRUE.equals(d.get("action_pending")));
            if (turn(d) >= turn) {
                return d;
            }
            Map<String, Object> a = host.chooseAction(seat, script.answer(d));
            Assert.assertTrue("answer rejected: " + a + " for " + d, Boolean.TRUE.equals(a.get("success")));
        }
        throw new AssertionError("turn " + turn + " never came");
    }

    /** The policy writes asynchronously: wait for the snapshot of the open question. */
    private static Map<String, Object> awaitSnapshot(GameHost host, int seq) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Map<String, Object> s = host.snapshotStatus();
            if (s.get("error") != null || Integer.valueOf(seq).equals(s.get("seq"))) {
                return s;
            }
            Thread.sleep(50);
        }
        return host.snapshotStatus();
    }

    /** Plays every listed seat with the script until {@code turn}; the highest turn reached. */
    private static int playOn(GameHost host, ScriptedSeat script, List<String> seats, int turn) throws Exception {
        int maxTurn = 0;
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            for (String seat : seats) {
                Map<String, Object> d = host.awaitDecision(seat, 1_000);
                Assert.assertNull("engine error", d.get("error"));
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    return Math.max(maxTurn, turn);
                }
                if (!Boolean.TRUE.equals(d.get("action_pending"))) {
                    continue;
                }
                maxTurn = Math.max(maxTurn, turn(d));
                if (maxTurn >= turn) {
                    return maxTurn;
                }
                Map<String, Object> a = host.chooseAction(seat, script.answer(d));
                Assert.assertTrue("answer rejected: " + a + " for " + d, Boolean.TRUE.equals(a.get("success")));
            }
        }
        throw new AssertionError("turn " + turn + " never came after the resume; reached " + maxTurn);
    }

    @Test(timeout = 300_000)
    public void aCpuGameResumesFromItsSnapshotAtTheSameQuestion() throws Exception {
        Path logDir = Files.createTempDirectory("snap-a");
        GameHost host = new GameHost(config("snap", logDir, "cpu", null));
        Assert.assertFalse(host.resumed());
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        Map<String, Object> early = playTo(host, script, "You", 3);
        Map<String, Object> first = awaitSnapshot(host, host.game().getGameSeq());
        Assert.assertNull("snapshot error: " + first, first.get("error"));
        Map<String, Object> a = host.chooseAction("You", script.answer(early));
        Assert.assertTrue(String.valueOf(a), Boolean.TRUE.equals(a.get("success")));
        Map<String, Object> paused = playTo(host, script, "You", 5);
        int seq = host.game().getGameSeq();
        Map<String, Object> status = awaitSnapshot(host, seq);
        Assert.assertNull("snapshot error: " + status, status.get("error"));
        Assert.assertEquals("snapshot of the open question", seq, status.get("seq"));
        Assert.assertTrue("a later snapshot: " + first + " -> " + status, seq > (Integer) first.get("seq"));
        Assert.assertTrue(Files.exists(logDir.resolve(Snapshot.FILE)));
        String boardBefore = String.valueOf(host.state("You").get("board"));
        LOG.info("paused at " + paused.get("context") + " seq " + seq + "; snapshot " + status);
        host.end();

        Path logDir2 = Files.createTempDirectory("snap-b");
        GameHost h2 = new GameHost(config("snap", logDir2, "cpu", logDir.resolve(Snapshot.FILE).toString()));
        Assert.assertTrue(h2.resumed());
        Assert.assertTrue(h2.swapped().isEmpty());
        Assert.assertEquals(List.of("You"), h2.seatNames());
        h2.start();
        Map<String, Object> again = h2.awaitDecision("You", 60_000);
        Assert.assertNull("engine error", again.get("error"));
        Assert.assertTrue("the question again: " + again, Boolean.TRUE.equals(again.get("action_pending")));
        Assert.assertEquals(paused.get("context"), again.get("context"));
        Assert.assertEquals(paused.get("message"), again.get("message"));
        Assert.assertEquals(paused.get("action_type"), again.get("action_type"));
        Assert.assertEquals("seqs continue", seq + 1, again.get("game_seq"));
        Assert.assertEquals("the board as it was, ids included", boardBefore, String.valueOf(h2.state("You").get("board")));
        int reached = playOn(h2, script, List.of("You"), 8);
        Assert.assertTrue("played on to turn " + reached, reached >= 8);
        // Every question on the way was answered at once, which never writes;
        // the one left open is written once the debounce has passed.
        Map<String, Object> resumedStatus = awaitSnapshot(h2, h2.game().getGameSeq());
        Assert.assertNull("resumed snapshot status: " + resumedStatus, resumedStatus.get("error"));
        Assert.assertTrue("the resumed game keeps its own snapshot", Files.exists(logDir2.resolve(Snapshot.FILE)));
        Assert.assertTrue("and its own record", Files.exists(logDir2.resolve("server_game_events.jsonl")));
        h2.end();
    }

    @Test(timeout = 300_000)
    public void theCpuSeatCanBeHandedToASeatAtResume() throws Exception {
        Path logDir = Files.createTempDirectory("snap-c");
        GameHost host = new GameHost(config("swap", logDir, "cpu", null));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        Map<String, Object> paused = playTo(host, script, "You", 5);
        Map<String, Object> status = awaitSnapshot(host, host.game().getGameSeq());
        Assert.assertNull("snapshot error: " + status, status.get("error"));
        host.end();

        Path logDir2 = Files.createTempDirectory("snap-d");
        GameHost h2 = new GameHost(config("swap", logDir2, "seat", logDir.resolve(Snapshot.FILE).toString()));
        Assert.assertEquals(List.of("CPU"), h2.swapped());
        Assert.assertEquals(List.of("You", "CPU"), h2.seatNames());
        h2.start();
        Map<String, Object> again = h2.awaitDecision("You", 60_000);
        Assert.assertEquals(paused.get("context"), again.get("context"));
        ScriptedSeat both = new ScriptedSeat();
        int reached = playOn(h2, both, List.of("You", "CPU"), 8);
        Assert.assertTrue("played on to turn " + reached, reached >= 8);
        long cpuDecisions = both.seen.stream().filter(d -> String.valueOf(d.get("context")).contains("(CPU)")).count();
        Assert.assertTrue("the CPU's turns are the seat's now", cpuDecisions > 0);
        h2.end();
    }

    @Test(timeout = 300_000)
    public void aSeatCannotBecomeTheCpuAndEverySeatMustBeNamed() throws Exception {
        Path logDir = Files.createTempDirectory("snap-e");
        GameHost host = new GameHost(config("refuse", logDir, "cpu", null));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        playTo(host, script, "You", 3);
        Map<String, Object> status = awaitSnapshot(host, host.game().getGameSeq());
        Assert.assertNull("snapshot error: " + status, status.get("error"));
        host.end();
        String from = logDir.resolve(Snapshot.FILE).toString();
        Path logDir2 = Files.createTempDirectory("snap-f");
        try {
            new GameHost(new GameHost.Config("refuse", "duel", 7L, logDir2.toString(),
                    List.of(new GameHost.SeatSpec("You", "cpu", BEARS, 6), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)),
                    false, null, 0, 0, "host", from, false));
            Assert.fail("a seat became the CPU");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("can't become the CPU"));
        }
        try {
            new GameHost(new GameHost.Config("refuse", "duel", 7L, logDir2.toString(),
                    List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0)), false, null, 0, 0, "host", from, false));
            Assert.fail("a player was left without a seat");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("every player in the snapshot needs a seat"));
        }
        try {
            new GameHost(new GameHost.Config("refuse", "duel", 7L, logDir2.toString(),
                    List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("Nobody", "cpu", BEARS, 6)),
                    false, null, 0, 0, "host", from, false));
            Assert.fail("an unknown name was seated");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("no player named Nobody"));
        }
    }
}
