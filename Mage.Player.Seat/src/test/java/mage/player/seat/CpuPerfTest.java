package mage.player.seat;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.players.Player;
import mage.game.PutToBattlefieldInfo;
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
 * change mid-game, the per-window ceiling lowers it, a held answer keeps the
 * window counting, a run of an opponent's identical triggers is thought
 * about once (the memo), and the snapshot write is skipped at a question
 * answered at once and given up when an answer comes in during it.
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

    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";
    private static final int SWARMS = 8;

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name);
        Assert.assertNotNull("card not in the DB: " + name, info);
        return info.createCard();
    }

    private static Player player(Game game, String name) {
        return game.getPlayers().values().stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
    }

    private static List<Card> cards(String name, int n) {
        List<Card> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(card(name));
        }
        return l;
    }

    /**
     * The reporter's shape (report a374dd040c): the seat with {@code SWARMS}
     * Scute Swarms and a library of Forests, so its land each turn is a run of
     * identical landfall triggers the CPU gets priority through, at a
     * precombat main it thinks in. The CPU (skill 1, a 2 s cap) has seven
     * Forests up and seven Giant Growths in hand: instants it could cast at
     * every trigger, with a target for each creature on the board, so a
     * think has a tree to search and runs to its cap the way the reporter's
     * did, and passing is still the right answer.
     */
    private static GameHost swarmHost(String id) throws Exception {
        GameHost host = new GameHost(new GameHost.Config(id, "duel", 11L, null,
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 1, 2)),
                false, null, 0, 0, "host", null, false));
        Game game = host.game();
        List<PutToBattlefieldInfo> swarms = new ArrayList<>();
        for (int i = 0; i < SWARMS; i++) {
            swarms.add(new PutToBattlefieldInfo(card("Scute Swarm"), false));
        }
        game.cheat(player(game, "You").getId(), cards("Forest", 12), List.of(), swarms, List.of(), List.of(), List.of());
        List<PutToBattlefieldInfo> forests = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            forests.add(new PutToBattlefieldInfo(card("Forest"), false));
        }
        game.cheat(player(game, "CPU").getId(), List.of(), cards("Giant Growth", 7), forests, List.of(), List.of(), List.of());
        return host;
    }

    /**
     * Plays the seat to {@code turn} the way seat_loop does under a "Pass until
     * the stack resolves" hold: every plain priority window with a stack is
     * answered {@code no}, marked {@code held} when asked to, and everything
     * else is the script's. Returns the perf records; counts the held passes.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> playHolding(GameHost host, int turn, boolean held, int[] passes) throws Exception {
        List<Map<String, Object>> perf = new ArrayList<>();
        ScriptedSeat script = new ScriptedSeat();
        for (int i = 0; i < 600; i++) {
            Map<String, Object> d = host.awaitDecision("You", 120_000);
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
            boolean stacked = d.get("stack") instanceof List<?> l && !l.isEmpty();
            if ("GAME_SELECT".equals(d.get("action_type")) && d.get("combat_phase") == null && stacked) {
                passes[0]++;
                host.chooseAction("You", held ? Map.of("choice", "no", "held", true) : Map.of("choice", "no"));
            } else {
                host.chooseAction("You", script.answer(d));
            }
        }
        return perf;
    }

    private static long ms(Map<String, Object> r) {
        return ((Number) r.get("ms")).longValue();
    }

    /** The CPU's thinks with a stack under them: the trigger runs. */
    private static List<Map<String, Object>> stackedThinks(List<Map<String, Object>> perf) {
        return of(perf, "cpu_think").stream().filter(t -> ((Number) t.get("stack")).intValue() > 0).toList();
    }

    @Test(timeout = 600_000)
    public void aRunOfAnOpponentsIdenticalTriggersIsThoughtAboutOnce() throws Exception {
        GameHost host = swarmHost("perf-memo");
        host.start();
        try {
            int[] passes = {0};
            List<Map<String, Object>> perf = playHolding(host, 4, true, passes);
            Assert.assertTrue("the seat passed through its landfall triggers: " + passes[0], passes[0] >= 2 * SWARMS);
            List<Map<String, Object>> thinks = stackedThinks(perf);
            // Turn 1's run and turn 3's: each starts with a real think (the turn
            // is part of the memo's key) and the rest are the memo's passes. A
            // trigger the CPU passes from the plan its think left (ComputerPlayer6
            // continues its chain when the state is the one it foresaw) has no
            // record at all, so a run of SWARMS triggers is SWARMS - 1 records.
            for (int turn : new int[]{1, 3}) {
                List<Map<String, Object>> run = thinks.stream()
                        .filter(t -> Integer.valueOf(turn).equals(t.get("turn")) && "PRECOMBAT_MAIN".equals(t.get("step")))
                        .filter(t -> ((Number) t.get("stack")).intValue() > 1 || t.get("memo") != null)
                        .toList();
                Assert.assertTrue("a record per trigger on turn " + turn + " but the chain's: " + run,
                        run.size() >= SWARMS - 2 && run.size() <= SWARMS);
                Map<String, Object> first = run.get(0);
                Assert.assertNull("the first is a simulation: " + first, first.get("memo"));
                Assert.assertNotNull(first.get("nodes"));
                for (Map<String, Object> t : run.subList(1, run.size())) {
                    Assert.assertEquals("the same question, passed from the memo: " + t, true, t.get("memo"));
                    Assert.assertEquals(0L, ms(t));
                    Assert.assertEquals(false, t.get("timed_out"));
                    Assert.assertEquals("PRECOMBAT_MAIN", t.get("step"));
                }
                System.out.println("turn " + turn + ": first think " + ms(first) + " ms (cap " + first.get("cap_s") + " s, timed_out "
                        + first.get("timed_out") + ", nodes " + first.get("nodes") + "), then " + (run.size() - 1) + " memo passes");
            }
        } finally {
            host.end();
        }
    }

    @Test(timeout = 600_000)
    public void aHeldAnswerKeepsTheWindowCountingAndAPlainOneEndsIt() throws Exception {
        // Held: the whole run of triggers is one window — the ceiling counts
        // across it — and the record says how many held passes it ran through.
        GameHost held = swarmHost("perf-held");
        held.start();
        try {
            int[] passes = {0};
            List<Map<String, Object>> perf = playHolding(held, 2, true, passes);
            Assert.assertEquals(SWARMS, passes[0]);
            List<Map<String, Object>> windows = of(perf, "table_window").stream()
                    .filter(w -> w.get("held") != null).toList();
            Assert.assertEquals("one window spans the held run: " + of(perf, "table_window"), 1, windows.size());
            Map<String, Object> run = windows.get(0);
            Assert.assertEquals("every held pass counted: " + run, SWARMS, run.get("held"));
            int thinks = ((Number) run.get("thinks")).intValue();
            Assert.assertTrue("every think in it (one trigger may be passed from the chain, unrecorded): " + run,
                    thinks >= SWARMS - 1 && thinks <= SWARMS);
            Assert.assertEquals("ended by the seat's own answer: " + run, "You", run.get("seat"));
        } finally {
            held.end();
        }
        // Plain: each pass is the person's answer and ends the window, as before.
        GameHost plain = swarmHost("perf-plain");
        plain.start();
        try {
            int[] passes = {0};
            List<Map<String, Object>> perf = playHolding(plain, 2, false, passes);
            Assert.assertEquals(SWARMS, passes[0]);
            List<Map<String, Object>> windows = of(perf, "table_window");
            Assert.assertTrue("a window per pass: " + windows, windows.size() >= SWARMS - 1);
            Assert.assertTrue("none held: " + windows, windows.stream().noneMatch(w -> w.get("held") != null));
            Assert.assertTrue("one think each: " + windows, windows.stream().allMatch(w -> Integer.valueOf(1).equals(w.get("thinks"))));
        } finally {
            plain.end();
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
            Assert.assertThrows(Snapshot.Aborted.class, () -> Snapshot.write(host.game(), host.seatKeys(), file, () -> true));
            Assert.assertArrayEquals("the previous snapshot stays", before, Files.readAllBytes(file));
            Assert.assertFalse("no temp file left", Files.exists(logDir.resolve(Snapshot.FILE + ".tmp")));
        } finally {
            host.end();
        }
    }
    @Test
    public void theLoopGuardPassesTheFourthIdenticalActInAStepAndForgetsAtTheNext() {
        SeatCpu.LoopGuard guard = new SeatCpu.LoopGuard();
        for (int i = 0; i < SeatCpu.LOOP_BREAK; i++) {
            Assert.assertFalse("act " + (i + 1), guard.repeated(16, PhaseStep.PRECOMBAT_MAIN, "untap@1"));
        }
        Assert.assertTrue("the fourth", guard.repeated(16, PhaseStep.PRECOMBAT_MAIN, "untap@1"));
        Assert.assertFalse("another board is another act", guard.repeated(16, PhaseStep.PRECOMBAT_MAIN, "untap@2"));
        Assert.assertFalse("the next step starts over", guard.repeated(16, PhaseStep.DECLARE_ATTACKERS, "untap@1"));
        Assert.assertFalse("so does the next turn", guard.repeated(17, PhaseStep.DECLARE_ATTACKERS, "untap@1"));
    }

    /**
     * Report 078b7f4b49: a CPU with Seeker of Skybreak ({T}: Untap target
     * creature) untapped it with itself at every priority of an opponent's
     * turn, 2,340 times, and the table never got its next question. The
     * search no longer offers an action that leaves the board as it was
     * (ComputerPlayer6.isNoOp), so the game goes on and the Seeker never
     * targets itself; the loop guard behind it has nothing to break.
     */
    @Test(timeout = 600_000)
    public void aSeekerOfSkybreakNeverUntapsItselfAndTheGameGoesOn() throws Exception {
        Path logDir = Files.createTempDirectory("perf-seeker");
        GameHost host = host("perf-seeker", 1, 1, logDir);
        Game game = host.game();
        game.cheat(player(game, "CPU").getId(), List.of(), List.of(),
                List.of(new PutToBattlefieldInfo(card("Seeker of Skybreak"), false)), List.of(), List.of(), List.of());
        host.start();
        try {
            List<Map<String, Object>> perf = playTo(host, 6, new ArrayList<>());
            List<String> lines = Files.readAllLines(logDir.resolve("server_game_events.jsonl"));
            long selfUntaps = lines.stream()
                    .filter(l -> l.contains("from Seeker of Skybreak") && l.contains("targeting Seeker of Skybreak"))
                    .count();
            Assert.assertEquals("the Seeker untapping itself", 0, selfUntaps);
            Assert.assertTrue("turn 6 was reached", lines.stream().anyMatch(l -> l.contains("\"turn\": 6") || l.contains("\"turn\":6")));
            Assert.assertTrue("nothing for the loop guard to break: " + of(perf, "cpu_loop_break"), of(perf, "cpu_loop_break").isEmpty());
        } finally {
            host.end();
        }
    }
}
