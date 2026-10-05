package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import mage.game.Exile;
import mage.game.ExileZone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

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
        host.setSnapshotIntervalMs(0); // the turn-5 question is written seconds after the turn-3 one
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

    /** Every perf record on the replies to the seat, collected as they drain. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> await(GameHost host, String seat, List<Map<String, Object>> perf) throws InterruptedException {
        Map<String, Object> d = host.awaitDecision(seat, 120_000);
        if (d.get("perf") != null) {
            perf.addAll((List<Map<String, Object>>) d.get("perf"));
        }
        Assert.assertNull("engine error", d.get("error"));
        Assert.assertTrue("decision expected: " + d, Boolean.TRUE.equals(d.get("action_pending")));
        return d;
    }

    /**
     * Answers questions until one whose snapshot write starts (a top-level
     * question; a payment or a target never writes); that question, open,
     * with the write running.
     */
    private static Map<String, Object> toAWrite(GameHost host, ScriptedSeat script, Set<Integer> started,
                                                List<Map<String, Object>> perf) throws Exception {
        for (int i = 0; i < 100; i++) {
            Map<String, Object> d = await(host, "You", perf);
            int seq = host.game().getGameSeq();
            for (int w = 0; w < 100 && !started.contains(seq); w++) {
                Thread.sleep(20);
            }
            if (started.contains(seq)) {
                return d;
            }
            Assert.assertTrue(Boolean.TRUE.equals(host.chooseAction("You", script.answer(d)).get("success")));
        }
        throw new AssertionError("no question was written");
    }

    private static List<Map<String, Object>> of(List<Map<String, Object>> perf, String kind) {
        return perf.stream().filter(r -> kind.equals(r.get("kind"))).toList();
    }

    /**
     * An answer given while the policy's write runs on a live host goes
     * through at once and the previous file stays; once a write has been
     * given up and the file is past its stale limit, the next write is held:
     * the answer waits for it, the file moves on, and it resumes at that
     * question. The harness's probe stalls each write for its first 2 s,
     * 5 ms at a time (the abort is checked between), so "during" is long
     * enough to answer in.
     */
    @Test(timeout = 300_000)
    public void anAnswerMidWriteGoesThroughAndAStaleFileIsHeldFor() throws Exception {
        Path logDir = Files.createTempDirectory("snap-mid");
        Path file = logDir.resolve(Snapshot.FILE);
        GameHost host = new GameHost(config("mid", logDir, "cpu", null));
        host.setSnapshotIntervalMs(0);
        ScriptedSeat script = new ScriptedSeat();
        List<Map<String, Object>> perf = new ArrayList<>();
        host.start();
        try {
            Map<String, Object> d = playTo(host, script, "You", 3);
            Map<String, Object> first = awaitSnapshot(host, host.game().getGameSeq());
            Assert.assertNull("snapshot error: " + first, first.get("error"));
            byte[] before = Files.readAllBytes(file);

            Set<Integer> started = ConcurrentHashMap.newKeySet();
            Map<Integer, Long> since = new ConcurrentHashMap<>();
            host.writeProbe = seq -> {
                started.add(seq);
                if (System.currentTimeMillis() - since.computeIfAbsent(seq, k -> System.currentTimeMillis()) < 2_000) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            host.setSnapshotDebounceMs(0); // every top-level question from here is written at once
            host.setSnapshotStaleMs(0); // a file of any age is stale: after one abort, the next write is held
            Assert.assertTrue(Boolean.TRUE.equals(host.chooseAction("You", script.answer(d)).get("success")));

            // Mid-write: the answer aborts it.
            Map<String, Object> mid = toAWrite(host, script, started, perf);
            long t0 = System.currentTimeMillis();
            Assert.assertTrue(Boolean.TRUE.equals(host.chooseAction("You", script.answer(mid)).get("success")));
            long answerMs = System.currentTimeMillis() - t0;
            Assert.assertTrue("the answer went through at once: " + answerMs + " ms", answerMs < 500);
            Assert.assertArrayEquals("the previous file stays", before, Files.readAllBytes(file));
            Assert.assertEquals(first.get("seq"), host.snapshotStatus().get("seq"));

            // The next write is held: the answer during it waits, the file moves on.
            Map<String, Object> heldAt = toAWrite(host, script, started, perf);
            int heldSeq = host.game().getGameSeq();
            t0 = System.currentTimeMillis();
            Assert.assertTrue(Boolean.TRUE.equals(host.chooseAction("You", script.answer(heldAt)).get("success")));
            long waitedMs = System.currentTimeMillis() - t0;
            Assert.assertTrue("the answer waited for the held write: " + waitedMs + " ms", waitedMs >= 1_000);
            Assert.assertEquals("written whole", heldSeq, host.snapshotStatus().get("seq"));
            host.writeProbe = null;
            host.setSnapshotStaleMs(60_000);
            await(host, "You", perf); // the next question's reply carries the records
            List<Map<String, Object>> writes = of(perf, "snapshot");
            Assert.assertTrue("an aborted write: " + writes, writes.stream().anyMatch(w -> Boolean.TRUE.equals(w.get("aborted"))));
            Assert.assertTrue("a held write of " + heldSeq + ": " + writes, writes.stream().anyMatch(w ->
                    Boolean.TRUE.equals(w.get("held")) && Integer.valueOf(heldSeq).equals(w.get("seq")) && w.get("bytes") != null));
            Assert.assertTrue("the answer waited on it (" + waitedMs + " ms): " + perf, of(perf, "answer_blocked").stream()
                    .anyMatch(b -> "snapshot".equals(b.get("by"))));
            LOG.info("mid-write answer " + answerMs + " ms; held write, answer waited " + waitedMs + " ms; " + writes);
        } finally {
            host.end();
        }

        Path logDir2 = Files.createTempDirectory("snap-mid-b");
        GameHost h2 = new GameHost(config("mid", logDir2, "cpu", file.toString()));
        h2.start();
        try {
            Map<String, Object> again = h2.awaitDecision("You", 60_000);
            Assert.assertTrue("the held question again: " + again, Boolean.TRUE.equals(again.get("action_pending")));
            int reached = playOn(h2, new ScriptedSeat(), List.of("You"), 6);
            Assert.assertTrue("played on to turn " + reached, reached >= 6);
        } finally {
            h2.end();
        }
    }

    /**
     * fullpod #50: a park reads the status, then ends the game, and a write
     * finishing in between put another board on disk than the one the park
     * described (seq 181 at turn 10 described, turn 11 resumed). The park's
     * read ({@link GameHost#snapshotFinal}) waits out a write in flight and
     * stops every one after it: its seq and turn are the file's, and stay so.
     */
    @Test(timeout = 300_000)
    public void aParksStatusNamesTheFileThatStays() throws Exception {
        Path logDir = Files.createTempDirectory("snap-final");
        Path file = logDir.resolve(Snapshot.FILE);
        GameHost host = new GameHost(config("final", logDir, "cpu", null));
        host.setSnapshotIntervalMs(0);
        ScriptedSeat script = new ScriptedSeat();
        List<Map<String, Object>> perf = new ArrayList<>();
        host.start();
        try {
            Map<String, Object> d = playTo(host, script, "You", 3);
            Map<String, Object> first = awaitSnapshot(host, host.game().getGameSeq());
            Assert.assertNull("snapshot error: " + first, first.get("error"));
            Assert.assertEquals("the status says the turn it was written at", 3, first.get("turn"));

            // Every write from here stalls 2 s before its first buffer: long
            // enough to park in the middle of one.
            Set<Integer> started = ConcurrentHashMap.newKeySet();
            Map<Integer, Long> since = new ConcurrentHashMap<>();
            host.writeProbe = seq -> {
                started.add(seq);
                while (System.currentTimeMillis() - since.computeIfAbsent(seq, k -> System.currentTimeMillis()) < 2_000) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            };
            host.setSnapshotDebounceMs(0);
            Assert.assertTrue(Boolean.TRUE.equals(host.chooseAction("You", script.answer(d)).get("success")));
            toAWrite(host, script, started, perf);
            int inFlight = host.game().getGameSeq();
            Assert.assertEquals("mid-write, the plain status still names the last file", first.get("seq"), host.snapshotStatus().get("seq"));

            Map<String, Object> parked = host.snapshotFinal();
            Assert.assertEquals("the park's status waited for the write in flight", inFlight, parked.get("seq"));
            Snapshot.Board board = Snapshot.read(file);
            Assert.assertEquals("its seq is the file's", parked.get("seq"), board.game().getGameSeq());
            Assert.assertEquals("its turn is the file's", parked.get("turn"), board.game().getTurnNum());

            // Nothing is written after it, though questions stay open past the debounce.
            host.writeProbe = null;
            byte[] kept = Files.readAllBytes(file);
            answerSlowly(host, script, 3, perf);
            Thread.sleep(500);
            Assert.assertEquals("no write after the park's read", parked.get("seq"), host.snapshotStatus().get("seq"));
            Assert.assertArrayEquals("the file it named stays", kept, Files.readAllBytes(file));
        } finally {
            host.end();
        }
    }

    /** Answers {@code n} questions, each after it has been open 150 ms (a write here takes a few). */
    private static void answerSlowly(GameHost host, ScriptedSeat script, int n, List<Map<String, Object>> perf) throws Exception {
        for (int i = 0; i < n; i++) {
            Map<String, Object> d = await(host, "You", perf);
            Thread.sleep(150);
            Assert.assertTrue(Boolean.TRUE.equals(host.chooseAction("You", script.answer(d)).get("success")));
        }
        await(host, "You", perf); // the records of the last one
    }

    private static GameHost.Config idConfig(Path logDir, String you, String cpu, String snapshotFrom) {
        return new GameHost.Config("ids", "duel", 7L, logDir.toString(),
                List.of(new GameHost.SeatSpec(you, "seat", BEARS, 0, 0, "You"), new GameHost.SeatSpec(cpu, "cpu", BEARS, 6, 0, "AI-1")),
                false, null, 0, 0, "host", snapshotFrom, false);
    }

    /**
     * fullpod #24: the host addresses a seat by the product's seat id, never
     * the engine username — a verb by the username finds no seat — and the
     * board records each player's id, so a resume matches players to seats by
     * it, whatever the specs are named; the CPU is found by its id too.
     */
    @Test(timeout = 300_000)
    public void seatsAreAddressedByIdAndABoardResumesByIt() throws Exception {
        Path logDir = Files.createTempDirectory("snap-ids");
        Path file = logDir.resolve("board.bin");
        ScriptedSeat script = new ScriptedSeat();
        GameHost host = new GameHost(idConfig(logDir, "You-1a", "AI1-1a", null));
        int turn;
        try {
            Assert.assertEquals(List.of("You"), host.seatNames());
            Assert.assertTrue("the CPU by its id", host.isCpu("AI-1"));
            Assert.assertFalse("not by its username", host.isCpu("AI1-1a"));
            Assert.assertThrows(IllegalArgumentException.class, () -> host.state("You-1a"));
            host.start();
            turn = turn(playTo(host, script, "You", 2));
            Snapshot.write(host.game(), host.seatKeys(), file);
        } finally {
            host.end();
        }
        Snapshot.Board board = Snapshot.read(file);
        Assert.assertEquals("the board names each player's seat id", java.util.Set.of("You", "AI-1"),
                new java.util.HashSet<>(board.seatKeys().values()));

        // Specs under other names: matched by id all the same.
        GameHost h2 = new GameHost(idConfig(logDir, "You-renamed", "AI1-renamed", file.toString()));
        try {
            Assert.assertTrue(h2.resumed());
            Assert.assertEquals(List.of("You"), h2.seatNames());
            Assert.assertTrue(h2.isCpu("AI-1"));
            h2.start();
            Map<String, Object> d = await(h2, "You", new ArrayList<>());
            Assert.assertEquals("the same question, by the seat's id", turn, turn(d));
        } finally {
            h2.end();
        }

        // An id the board doesn't have is refused, naming what it has.
        IllegalArgumentException ex = Assert.assertThrows(IllegalArgumentException.class, () -> new GameHost(
                new GameHost.Config("ids", "duel", 7L, logDir.toString(),
                        List.of(new GameHost.SeatSpec("You-1a", "seat", BEARS, 0, 0, "P2"),
                                new GameHost.SeatSpec("AI1-1a", "cpu", BEARS, 6, 0, "AI-1")),
                        false, null, 0, 0, "host", file.toString(), false)));
        Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("seat id P2"));
    }

    /**
     * A question asked inside the interval and left open is written when the
     * interval ends (fullpod #25): the board a park finds is the question
     * the person walked away from, not the last one written before it.
     */
    @Test(timeout = 300_000)
    public void aQuestionLeftOpenInsideTheIntervalIsWrittenWhenItEnds() throws Exception {
        Path logDir = Files.createTempDirectory("snap-interval-end");
        GameHost host = new GameHost(config("interval-end", logDir, "cpu", null));
        host.setSnapshotDebounceMs(0);
        host.setSnapshotIntervalMs(8_000);
        ScriptedSeat script = new ScriptedSeat();
        List<Map<String, Object>> perf = new ArrayList<>();
        host.start();
        try {
            answerSlowly(host, script, 4, perf); // the first is written; the next ones are inside the interval
            int open = host.game().getGameSeq();
            Assert.assertNotEquals("the open question isn't the file yet", open, host.snapshotStatus().get("seq"));
            Map<String, Object> status = host.snapshotStatus();
            for (int i = 0; i < 400 && !Integer.valueOf(open).equals(status.get("seq")) && status.get("error") == null; i++) {
                Thread.sleep(50);
                status = host.snapshotStatus();
            }
            Assert.assertNull("snapshot error: " + status, status.get("error"));
            Assert.assertEquals("the question left open, once the interval is over", open, status.get("seq"));
        } finally {
            host.end();
        }
    }

    /**
     * At most one completed write an interval, however many questions stay
     * open past the debounce; without the interval, every one is written,
     * and each once.
     */
    @Test(timeout = 300_000)
    public void atMostOneWriteAnInterval() throws Exception {
        Path logDir = Files.createTempDirectory("snap-interval");
        GameHost host = new GameHost(config("interval", logDir, "cpu", null));
        host.setSnapshotDebounceMs(0);
        host.setSnapshotIntervalMs(600_000);
        ScriptedSeat script = new ScriptedSeat();
        List<Map<String, Object>> perf = new ArrayList<>();
        host.start();
        try {
            answerSlowly(host, script, 30, perf);
            List<Map<String, Object>> written = of(perf, "snapshot").stream().filter(r -> r.get("bytes") != null).toList();
            Assert.assertEquals("one write in the interval: " + of(perf, "snapshot"), 1, written.size());

            perf.clear();
            host.setSnapshotIntervalMs(0);
            answerSlowly(host, script, 10, perf);
            written = of(perf, "snapshot").stream().filter(r -> r.get("bytes") != null).toList();
            Assert.assertTrue("a write at each top-level question: " + written, written.size() > 1);
            Assert.assertEquals("each once: " + written, written.size(), written.stream().map(r -> r.get("seq")).distinct().count());
        } finally {
            host.end();
        }
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
    public void aSeatCannotBecomeTheCpuAndEverySeatMustBeNamedOnce() throws Exception {
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
            Assert.fail("an unknown seat was seated");
        } catch (IllegalArgumentException expected) {
            // The board records seat ids (fullpod #24); a spec given none is keyed by its name.
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("no player with seat id Nobody"));
        }
        // Two seats under one name would both be matched to one saved player,
        // and the guard above would still pass (fullpod issue #24).
        try {
            new GameHost(new GameHost.Config("refuse", "duel", 7L, logDir2.toString(),
                    List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("You", "seat", BEARS, 0),
                            new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)),
                    false, null, 0, 0, "host", from, false));
            Assert.fail("two seats named You were resumed");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("two seats are named You"));
        }
    }

    // ---- a board saved by another engine process (fullpod issue #22) ----
    //
    // Exile's permanent zone is keyed by a UUID; it used to be drawn at random
    // once per JVM, so a snapshot read by the next process had its permanent
    // exile under a key nobody asked for, and the first plain exile after the
    // resume threw. The fixture is such a board: the path-and-plains deck
    // against the bears, written at the first question of turn 2 by another
    // process whose permanent zone had a key of its own. Every resume above
    // happens in the JVM that wrote the file, where the key always matched.

    static final String PATHS = "src/test/resources/decks/path_plains.dck";
    static final String FOREIGN_FIXTURE = "src/test/resources/snapshots/foreign_permanent_exile.bin";

    private static GameHost.Config foreignConfig(Path logDir, String cpuKind, String snapshotFrom) {
        return new GameHost.Config("foreign", "duel", 7L, logDir.toString(),
                List.of(new GameHost.SeatSpec("You", "seat", PATHS, 0), new GameHost.SeatSpec("CPU", cpuKind, BEARS, 6)),
                false, null, 0, 0, "host", snapshotFrom, false);
    }

    /** Exile's zones and the key its permanent zone has in this JVM. */
    @SuppressWarnings("unchecked")
    private static Map<UUID, ExileZone> zones(Exile exile) throws ReflectiveOperationException {
        Field f = Exile.class.getDeclaredField("exileZones");
        f.setAccessible(true);
        return (Map<UUID, ExileZone>) f.get(exile);
    }

    private static UUID permanentKey() throws ReflectiveOperationException {
        Field f = Exile.class.getDeclaredField("PERMANENT");
        f.setAccessible(true);
        return (UUID) f.get(null);
    }

    /** Puts the permanent zone under {@code key}, where another process's Exile had it; the order stays. */
    private static void rekeyPermanent(Exile exile, UUID key) throws ReflectiveOperationException {
        UUID ours = permanentKey();
        Map<UUID, ExileZone> zones = zones(exile);
        Map<UUID, ExileZone> moved = new LinkedHashMap<>();
        for (Map.Entry<UUID, ExileZone> e : zones.entrySet()) {
            if (e.getKey().equals(ours)) {
                ExileZone zone = new ExileZone(key, e.getValue().getName());
                zone.addAll(e.getValue());
                moved.put(key, zone);
            } else {
                moved.put(e.getKey(), e.getValue());
            }
        }
        zones.clear();
        zones.putAll(moved);
    }

    private static Exile roundTrip(Exile exile) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(exile);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Exile) in.readObject();
        }
    }

    /**
     * Re-records the fixture: {@code mvn test -pl Mage.Player.Seat
     * -Dtest=SnapshotResumeTest#recordForeignFixture -Dfullpod.recordForeignFixture=true}.
     * Needed only when a class the game serializes changed shape (a pin
     * refresh, a fork commit that adds a field) and the resume test says the
     * fixture no longer reads. The permanent zone is put under a random key
     * before the write, which is what a process from before the constant
     * key wrote, so the fixture keeps testing the heal on read; and it is
     * written in format 1, so it keeps testing the by-name upgrade too.
     */
    @Test(timeout = 300_000)
    public void recordForeignFixture() throws Exception {
        org.junit.Assume.assumeTrue("records only on request", Boolean.getBoolean("fullpod.recordForeignFixture"));
        Path logDir = Files.createTempDirectory("snap-rec");
        GameHost host = new GameHost(foreignConfig(logDir, "cpu", null));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        playTo(host, script, "You", 2);
        rekeyPermanent(host.game().getExile(), UUID.randomUUID());
        // Format 1, the version and the game with no seat map: the fixture is a
        // board from before seat ids were recorded, which is what
        // aBoardFromBeforeIdsResumesByNameAndIsAddressedById resumes (fullpod #24).
        // Snapshot.write only writes the current format.
        Path fixture = Path.of(FOREIGN_FIXTURE);
        try (ObjectOutputStream out = new ObjectOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(fixture)))) {
            out.writeInt(1);
            out.writeObject(host.game());
        }
        long bytes = Files.size(fixture);
        LOG.info("recorded " + FOREIGN_FIXTURE + " (" + bytes + " bytes)");
        host.end();
    }

    @Test(timeout = 300_000)
    public void aBoardSavedByAnotherProcessStillHasItsPermanentExile() throws Exception {
        Path logDir = Files.createTempDirectory("snap-foreign");
        GameHost h2;
        try {
            h2 = new GameHost(foreignConfig(logDir, "seat", Path.of(FOREIGN_FIXTURE).toAbsolutePath().toString()));
        } catch (InvalidClassException ex) {
            throw new AssertionError("the fixture no longer reads (a serialized class changed shape): "
                    + "re-record it with recordForeignFixture's command. " + ex, ex);
        }
        Assert.assertTrue(h2.resumed());
        Assert.assertEquals(List.of("CPU"), h2.swapped());
        Assert.assertNotNull("the permanent exile after the resume", h2.game().getExile().getPermanentExile());
        h2.start();
        // Both seats scripted: the bears come down and You's Paths exile them,
        // each a plain exile into the permanent zone.
        ScriptedSeat both = new ScriptedSeat();
        playOn(h2, both, List.of("You", "CPU"), 8);
        ExileZone permanent = h2.game().getExile().getPermanentExile();
        Assert.assertNotNull(permanent);
        Assert.assertFalse("a Path to Exile resolved into the permanent exile", permanent.isEmpty());
        for (String line : h2.logLines()) {
            Assert.assertFalse(line, line.contains("Auto-restored") || line.contains("game error"));
        }
        h2.end();
    }

    /**
     * The upgrade (fullpod #24): a board saved before seat ids were recorded
     * — the foreign fixture is one — resumed by a product that now gives
     * every seat its id. The players are matched by name, as they were
     * saved, and the seats are addressed by id from then on.
     */
    @Test(timeout = 120_000)
    public void aBoardFromBeforeIdsResumesByNameAndIsAddressedById() throws Exception {
        Path logDir = Files.createTempDirectory("snap-v1");
        GameHost h2;
        try {
            h2 = new GameHost(new GameHost.Config("foreign", "duel", 7L, logDir.toString(),
                    List.of(new GameHost.SeatSpec("You", "seat", PATHS, 0, 0, "P1"), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6, 0, "AI-1")),
                    false, null, 0, 0, "host", Path.of(FOREIGN_FIXTURE).toAbsolutePath().toString(), false));
        } catch (InvalidClassException ex) {
            throw new AssertionError("the fixture no longer reads (a serialized class changed shape): "
                    + "re-record it with recordForeignFixture's command. " + ex, ex);
        }
        try {
            Assert.assertTrue(h2.resumed());
            Assert.assertEquals(List.of("P1"), h2.seatNames());
            Assert.assertTrue("the CPU by its id", h2.isCpu("AI-1"));
            Assert.assertEquals("its keys are the ids now", java.util.Set.of("P1", "AI-1"), new java.util.HashSet<>(h2.seatKeys().values()));
            h2.start();
            Assert.assertTrue(Boolean.TRUE.equals(await(h2, "P1", new ArrayList<>()).get("action_pending")));
        } finally {
            h2.end();
        }
    }

    @Test
    public void anExileUnderAForeignKeyIsHealedOnRead() throws Exception {
        UUID card = UUID.randomUUID();
        UUID named = UUID.randomUUID();
        Exile exile = new Exile();
        exile.createZone(named, "Augury");
        exile.getPermanentExile().add(card);
        rekeyPermanent(exile, UUID.randomUUID());
        Assert.assertNull("the other process's key", exile.getPermanentExile());

        Exile healed = roundTrip(exile);
        ExileZone permanent = healed.getPermanentExile();
        Assert.assertNotNull("re-keyed on read", permanent);
        Assert.assertEquals(permanentKey(), permanent.getId());
        Assert.assertEquals("its cards come along", List.of(card), new ArrayList<>(permanent));
        Assert.assertEquals("and nothing else moved", List.of(permanentKey(), named), new ArrayList<>(zones(healed).keySet()));

        // No zone of that name at all: an empty one, so the next exile has somewhere to go.
        Exile none = new Exile();
        zones(none).clear();
        Assert.assertNotNull(roundTrip(none).getPermanentExile());
    }
    // ---- the size of a 4-player board (fullpod issue #26) ----

    static final String INFERNO = "src/test/resources/decks/heavenly_inferno.dck";
    static final String HUNGRY = "src/test/resources/decks/power_hungry.dck";

    /** Counts what goes through it. */
    private static final class Counting extends OutputStream {
        long bytes;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes += len;
        }
    }

    /** Serializes {@code o} as a snapshot does (gzip): the bytes, and every class named in the stream. */
    private static long gzipBytes(Object o, List<String> classes) throws Exception {
        Counting count = new Counting();
        try (GZIPOutputStream gz = new GZIPOutputStream(count);
             ObjectOutputStream out = new ObjectOutputStream(gz) {
                 @Override
                 protected void annotateClass(Class<?> cl) {
                     if (classes != null) {
                         classes.add(cl.getName());
                     }
                 }
             }) {
            out.writeObject(o);
        }
        return count.bytes;
    }

    /** Each CPU's kept search tree ({@code ComputerPlayer6.root}), by name; null when it has none. */
    private static Map<String, Object> roots(GameHost host) throws ReflectiveOperationException {
        Field f = mage.player.ai.ComputerPlayer6.class.getDeclaredField("root");
        f.setAccessible(true);
        Map<String, Object> r = new LinkedHashMap<>();
        for (mage.players.Player p : host.game().getPlayers().values()) {
            if (p instanceof mage.player.ai.ComputerPlayer6) {
                r.put(p.getName(), f.get(p));
            }
        }
        return r;
    }

    /**
     * A 4-player Commander board — a scripted seat and three CPUs on the
     * precons at the product's settings (skill 1, a 3 s cap) — written at
     * the first question of every turn and at every question right after a
     * CPU thought, when its search tree is biggest. The tree (a branch of
     * full game copies the CPU keeps between thinks) is not in the file:
     * the file never names SimulationNode2, however big the trees are.
     * {@code -Dfullpod.sizeProbeTurns=N} plays further (default 8); the log
     * has every write's size and the trees' own.
     */
    @Test(timeout = 1_800_000)
    public void aFourPlayerBoardLeavesTheCpusSearchOut() throws Exception {
        int turns = Integer.getInteger("fullpod.sizeProbeTurns", 8);
        Path logDir = Files.createTempDirectory("snap-pod");
        Path file = logDir.resolve(Snapshot.FILE);
        GameHost host = new GameHost(new GameHost.Config("pod-size", "commander", 3L, logDir.toString(),
                List.of(new GameHost.SeatSpec("You", "seat", INFERNO, 0),
                        new GameHost.SeatSpec("CPU1", "cpu", HUNGRY, 1, 3),
                        new GameHost.SeatSpec("CPU2", "cpu", INFERNO, 1, 3),
                        new GameHost.SeatSpec("CPU3", "cpu", HUNGRY, 1, 3)),
                true, null, 0, 1, "host", null, false));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        int lastTurn = -1;
        Map<String, Object> lastRoots = new LinkedHashMap<>();
        long maxFile = 0;
        long maxTrees = 0;
        int written = 0;
        int withTrees = 0;
        boolean checkedClasses = false;
        try {
            for (int i = 0; i < 4_000; i++) {
                Map<String, Object> d = host.awaitDecision("You", 300_000);
                Assert.assertNull("engine error", d.get("error"));
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertTrue("decision expected: " + d, Boolean.TRUE.equals(d.get("action_pending")));
                int turn = turn(d);
                if (turn > turns) {
                    break;
                }
                Map<String, Object> roots = roots(host);
                boolean thought = false;
                for (Map.Entry<String, Object> e : roots.entrySet()) {
                    if (e.getValue() != null && e.getValue() != lastRoots.get(e.getKey())) {
                        thought = true;
                    }
                }
                if (turn != lastTurn || thought) {
                    lastTurn = turn;
                    lastRoots = roots;
                    long t0 = System.currentTimeMillis();
                    long bytes = Snapshot.write(host.game(), host.seatKeys(), file);
                    long ms = System.currentTimeMillis() - t0;
                    long trees = 0;
                    for (Object root : roots.values()) {
                        if (root != null) {
                            trees += gzipBytes(root, null);
                        }
                    }
                    written++;
                    maxFile = Math.max(maxFile, bytes);
                    maxTrees = Math.max(maxTrees, trees);
                    LOG.info("POD_SIZE turn " + turn + " seq " + host.game().getGameSeq() + " file " + bytes + " B in " + ms
                            + " ms; the CPUs' trees alone " + trees + " B" + (thought ? " (a CPU just thought)" : ""));
                    if (trees > 0) {
                        withTrees++;
                        if (!checkedClasses) {
                            checkedClasses = true;
                            List<String> classes = new ArrayList<>();
                            gzipBytes(host.game(), classes);
                            Assert.assertFalse("the file carries a CPU's search tree", classes.contains("mage.player.ai.SimulationNode2"));
                        }
                    }
                }
                Map<String, Object> a = host.chooseAction("You", script.answer(d));
                Assert.assertTrue("answer rejected: " + a + " for " + d, Boolean.TRUE.equals(a.get("success")));
            }
            LOG.info("POD_SIZE " + written + " writes to turn " + lastTurn + ", " + withTrees + " with a CPU tree kept; largest file "
                    + maxFile + " B, largest trees " + maxTrees + " B");
            Assert.assertTrue("a CPU kept a tree at some write, or the probe proves nothing", checkedClasses);
            Assert.assertNotNull("the file reads back", Snapshot.read(file));
        } finally {
            host.end();
        }
    }
}
