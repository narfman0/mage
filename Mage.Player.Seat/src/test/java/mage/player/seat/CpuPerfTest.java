package mage.player.seat;

import mage.cards.repository.CardScanner;
import mage.constants.RangeOfInfluence;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The CPU's think cap and the perf records (SeatCpu, GameHost): every think
 * is timed and reported against its cap, the cap is the seat's and can
 * change mid-game, the per-window ceiling lowers it, and the snapshot write
 * is skipped at a question answered at once and given up when an answer
 * comes in during it.
 */
public class CpuPerfTest {

    private static final String BEARS = GameHostTest.BEARS;

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    private static GameHost host(String id, int skill, int maxThinkSecs, Path logDir) throws Exception {
        return new GameHost(new GameHost.Config(id, "duel", 11L, logDir == null ? null : logDir.toString(),
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, skill, maxThinkSecs)),
                false, null, 0, 0, "host", null, logDir != null));
    }

    /** Plays the seat with the script to {@code turn}, collecting every perf record the replies carry. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> playTo(GameHost host, int turn, List<Map<String, Object>> perf) throws Exception {
        ScriptedSeat script = new ScriptedSeat();
        for (int i = 0; i < 400; i++) {
            Map<String, Object> d = host.awaitDecision("You", 60_000);
            if (d.get("perf") != null) {
                perf.addAll((List<Map<String, Object>>) d.get("perf"));
            }
            Assert.assertNull("engine error", d.get("error"));
            if (Boolean.TRUE.equals(d.get("game_over")) || !Boolean.TRUE.equals(d.get("action_pending"))) {
                break;
            }
            String context = String.valueOf(d.get("context"));
            if (Integer.parseInt(context.substring(1, context.indexOf(' '))) >= turn) {
                break;
            }
            host.chooseAction("You", script.answer(d));
        }
        return perf;
    }

    private static List<Map<String, Object>> of(List<Map<String, Object>> perf, String kind) {
        return perf.stream().filter(r -> kind.equals(r.get("kind"))).toList();
    }

    @Test
    public void aCopyKeepsTheCapAndTheDepth() {
        SeatCpu cpu = new SeatCpu("CPU", RangeOfInfluence.ALL, 6);
        Assert.assertEquals("upstream's rule: 3 s a skill point", 18, cpu.getMaxThinkSecs());
        cpu.setMaxThinkSecs(2);
        cpu.setMaxDepth(5);
        SeatCpu copy = cpu.copy();
        Assert.assertEquals(2, copy.getMaxThinkSecs());
        Assert.assertEquals(5, copy.getMaxDepth());
        cpu.setMaxThinkSecs(0);
        Assert.assertEquals("at least a second", 1, cpu.getMaxThinkSecs());
    }

    @Test(timeout = 300_000)
    public void everyThinkIsTimedAgainstTheSeatsCapWhichChangesMidGame() throws Exception {
        GameHost host = host("perf-cap", 1, 1, null);
        Assert.assertTrue(host.isCpu("CPU"));
        Assert.assertFalse(host.isCpu("You"));
        host.start();
        try {
            List<Map<String, Object>> perf = playTo(host, 4, new ArrayList<>());
            List<Map<String, Object>> thinks = of(perf, "cpu_think");
            Assert.assertFalse("the CPU thought by turn 4: " + perf, thinks.isEmpty());
            for (Map<String, Object> t : thinks) {
                Assert.assertEquals("CPU", t.get("seat"));
                Assert.assertEquals("the seat's cap: " + t, 1, t.get("cap_s"));
                Assert.assertTrue("stopped at its cap: " + t, ((Number) t.get("ms")).longValue() < 1_500);
                Assert.assertEquals("skill 1 looks 4 deep", 4, t.get("depth"));
                Assert.assertNotNull(t.get("turn"));
                Assert.assertNotNull(t.get("permanents"));
            }
            Assert.assertFalse("a window with a think is recorded: " + perf, of(perf, "cpu_window").isEmpty());
            List<Map<String, Object>> tableWindows = of(perf, "table_window");
            Assert.assertFalse("the table's wait between two questions is recorded: " + perf, tableWindows.isEmpty());
            Assert.assertTrue(tableWindows.stream().allMatch(w -> ((Number) w.get("thinks")).intValue() > 0));

            host.setCpuMaxThinkSecs("CPU", 2);
            List<Map<String, Object>> later = of(playTo(host, 7, new ArrayList<>()), "cpu_think");
            Assert.assertFalse("the CPU thought again", later.isEmpty());
            Assert.assertTrue("the new cap from the next think on: " + later,
                    later.stream().allMatch(t -> Integer.valueOf(2).equals(t.get("cap_s"))));
            Assert.assertThrows(IllegalArgumentException.class, () -> host.setCpuMaxThinkSecs("You", 2));
        } finally {
            host.end();
        }
    }

    @Test(timeout = 300_000)
    public void theWindowCeilingLowersEveryThinkOnceSpent() throws Exception {
        GameHost host = host("perf-window", 1, 5, null);
        host.setCpuWindowMs(1_000); // spent before it starts: every think gets the 1 s floor
        host.start();
        try {
            List<Map<String, Object>> thinks = of(playTo(host, 4, new ArrayList<>()), "cpu_think");
            Assert.assertFalse(thinks.isEmpty());
            Assert.assertTrue("capped by the window, not the seat's 5 s: " + thinks,
                    thinks.stream().allMatch(t -> Integer.valueOf(1).equals(t.get("cap_s"))));
        } finally {
            host.end();
        }
    }

    @Test(timeout = 300_000)
    public void aQuestionAnsweredAtOnceIsNeverWritten() throws Exception {
        Path logDir = Files.createTempDirectory("perf-snap");
        GameHost host = host("perf-snap", 1, 1, logDir);
        host.setSnapshotDebounceMs(60_000);
        host.start();
        try {
            List<Map<String, Object>> perf = playTo(host, 3, new ArrayList<>());
            Assert.assertTrue("no write at a question answered within the debounce: " + perf, of(perf, "snapshot").isEmpty());
            Assert.assertNull(host.snapshotStatus().get("seq"));
            Assert.assertFalse(Files.exists(logDir.resolve(Snapshot.FILE)));

            // The command writes at once, and says what it cost.
            Map<String, Object> status = host.snapshotNow();
            Assert.assertNull("snapshot error: " + status, status.get("error"));
            Assert.assertTrue(Files.exists(logDir.resolve(Snapshot.FILE)));
            Map<String, Object> d = host.awaitDecision("You", 1_000);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> after = (List<Map<String, Object>>) d.get("perf");
            Assert.assertNotNull("the write is a perf record: " + d, after);
            Map<String, Object> write = of(after, "snapshot").get(0);
            Assert.assertTrue(((Number) write.get("bytes")).longValue() > 0);
            Assert.assertNotNull(write.get("ms"));
        } finally {
            host.end();
        }
    }

    @Test(timeout = 300_000)
    public void anAbortedWriteLeavesThePreviousFile() throws Exception {
        Path logDir = Files.createTempDirectory("perf-abort");
        GameHost host = host("perf-abort", 1, 1, logDir);
        host.setSnapshotDebounceMs(60_000);
        host.start();
        try {
            playTo(host, 2, new ArrayList<>());
            Assert.assertNull(host.snapshotNow().get("error"));
            Path file = logDir.resolve(Snapshot.FILE);
            byte[] before = Files.readAllBytes(file);
            Assert.assertThrows(Snapshot.Aborted.class, () -> Snapshot.write(host.game(), file, () -> true));
            Assert.assertArrayEquals("the previous snapshot stays", before, Files.readAllBytes(file));
            Assert.assertFalse("no temp file left", Files.exists(logDir.resolve(Snapshot.FILE + ".tmp")));
        } finally {
            host.end();
        }
    }
}
