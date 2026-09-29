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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
     * key wrote, so the fixture keeps testing the heal on read.
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
        long bytes = Snapshot.write(host.game(), Path.of(FOREIGN_FIXTURE));
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
}
