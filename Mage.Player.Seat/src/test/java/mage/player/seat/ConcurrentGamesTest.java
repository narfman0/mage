package mage.player.seat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Games running at the same time in one JVM draw from their own generators
 * (RandomUtil.bindThread on the game thread), so each replays from its own
 * seed: the same seeded game, played alone and played alongside another,
 * leaves the same record. Two scripted seats, no CPU (its choices are not
 * deterministic), stopped at the same turn.
 */
public class ConcurrentGamesTest {

    private static final Logger LOG = Logger.getLogger(ConcurrentGamesTest.class);
    // Drive a turn past the compared span, so the turn_change that ends the
    // span is on disk in both records before either game is stopped.
    private static final int COMPARE_TO_TURN = 6;
    private static final int STOP_AT_TURN = COMPARE_TO_TURN + 1;

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 300_000)
    public void aSeededGameLeavesTheSameRecordAloneAndAlongsideAnother() throws Exception {
        List<String> alone = play("alone", 11L);
        Assert.assertTrue("a game happened: " + alone.size() + " lines", alone.size() > 40);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<String>> again = pool.submit(() -> play("again", 11L));
            Future<List<String>> other = pool.submit(() -> play("other", 12L));
            List<String> againRecord = again.get();
            List<String> otherRecord = other.get();
            Assert.assertEquals("a different seed is a different game", false, alone.equals(otherRecord));
            for (int i = 0; i < Math.min(alone.size(), againRecord.size()); i++) {
                Assert.assertEquals("first difference at line " + i + " of " + alone.size(), alone.get(i), againRecord.get(i));
            }
            Assert.assertEquals("record length", alone.size(), againRecord.size());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Plays a seeded scripted duel into STOP_AT_TURN and returns the essence of its record. */
    static List<String> play(String id, long seed) throws Exception {
        Path logDir = Files.createTempDirectory("seat-" + id);
        GameHost host = new GameHost(new GameHost.Config(id, "duel", seed, logDir.toString(),
                List.of(new GameHost.SeatSpec("P1", "seat", GameHostTest.BEARS, 0),
                        new GameHost.SeatSpec("P2", "seat", GameHostTest.BEARS, 0)), false));
        AtomicBoolean stop = new AtomicBoolean();
        Thread p1 = new Thread(() -> drive(host, id, "P1", stop), "drive-" + id + "-P1");
        Thread p2 = new Thread(() -> drive(host, id, "P2", stop), "drive-" + id + "-P2");
        host.start();
        try {
            p1.start();
            p2.start();
            p1.join(240_000);
            p2.join(240_000);
        } finally {
            stop.set(true);
            host.end();
        }
        return essence(logDir.resolve("server_game_events.jsonl"));
    }

    private static void drive(GameHost host, String id, String seat, AtomicBoolean stop) {
        ScriptedSeat script = new ScriptedSeat();
        boolean loggedHand = false;
        try {
            while (!stop.get()) {
                Map<String, Object> d = host.awaitDecision(seat, 2_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    stop.set(true);
                    return;
                }
                if (!Boolean.TRUE.equals(d.get("action_pending"))) {
                    continue; // the other seat's question, or nothing yet
                }
                String context = String.valueOf(d.get("context"));
                if (!loggedHand) {
                    loggedHand = true;
                    LOG.info("HAND " + id + " " + seat + " " + hand(d));
                }
                int turn = Integer.parseInt(context.substring(1, context.indexOf(' ')));
                if (turn >= STOP_AT_TURN) {
                    stop.set(true);
                    return;
                }
                d = withChoicesByShortId(d);
                Map<String, Object> answer = host.chooseAction(seat, byShortId(d, script.answer(d)));
                if (!Boolean.TRUE.equals(answer.get("success"))) {
                    LOG.warn(seat + ": answer rejected: " + answer + " for " + d);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The engine lists playables in an order that varies between runs (hash
     * sets), so "the first land" can be a different Forest each time. Sort the
     * choices by short id (the same object in every run of a seed) before the
     * script picks, and answer by that id -- what the recorded goldens do.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withChoicesByShortId(Map<String, Object> d) {
        List<Map<String, Object>> choices = new ArrayList<>(ScriptedSeat.choices(d));
        choices.sort(java.util.Comparator.comparingLong(ConcurrentGamesTest::idOrder));
        Map<String, Object> copy = new java.util.HashMap<>(d);
        copy.put("choices", choices);
        return copy;
    }

    private static long idOrder(Map<String, Object> option) {
        String id = String.valueOf(option.get("id"));
        return id.matches("[a-z]\\d+") ? Long.parseLong(id.substring(1)) : Long.MAX_VALUE;
    }

    /** The choice's short id for an answer the script gave by index. */
    private static Map<String, Object> byShortId(Map<String, Object> d, Map<String, Object> args) {
        Object choice = args.get("choice");
        if (choice instanceof String c && c.matches("\\d+")) {
            for (Map<String, Object> option : ScriptedSeat.choices(d)) {
                if (c.equals(String.valueOf(option.get("index"))) && option.get("id") != null) {
                    args.put("choice", String.valueOf(option.get("id")));
                    break;
                }
            }
        }
        return args;
    }

    /**
     * What two runs of the same game must agree on: every decision (who was
     * asked what, and the answer), every game action and every turn and phase
     * change, up to the turn the game was stopped at. Left out: the choices
     * offered (the engine lists targets from hash sets, in varying order), the
     * {@code [abc]} object refs in log text (UUID-derived), and the ending
     * ({@code end_game} writes who "lost" by which seat happened to be waiting).
     */
    static List<String> essence(Path record) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(record)) {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            String type = o.get("type").getAsString();
            if ("turn_change".equals(type) && o.get("turn").getAsInt() >= COMPARE_TO_TURN) {
                break;
            }
            switch (type) {
                case "decision" -> out.add("decision " + o.get("player") + " " + o.get("query_type") + " "
                        + strip(o.get("message").getAsString()) + " -> " + o.get("response"));
                case "game_action" -> {
                    String message = o.get("message").getAsString();
                    if (!message.endsWith(" has lost the game.") && !message.contains(" conceded")) {
                        out.add("action " + strip(message));
                    }
                }
                case "turn_change", "phase_change" -> out.add(type + " " + o.get("turn") + " " + o.get("phase") + " "
                        + o.get("step") + " " + o.get("active_player"));
                default -> {
                }
            }
        }
        return out;
    }

    /** The seat's hand as the decision shows it: short ids, so two deals can be told apart. */
    @SuppressWarnings("unchecked")
    private static String hand(Map<String, Object> d) {
        Object board = d.get("board");
        if (!(board instanceof List<?> players)) {
            return "?";
        }
        for (Object p : players) {
            Map<String, Object> player = (Map<String, Object>) p;
            if (Boolean.TRUE.equals(player.get("is_you")) && player.get("hand") instanceof List<?> cards) {
                List<String> ids = new ArrayList<>();
                for (Object c : cards) {
                    Map<String, Object> card = (Map<String, Object>) c;
                    ids.add(card.get("id") + ":" + card.get("name"));
                }
                return String.join(" ", ids);
            }
        }
        return "?";
    }

    private static String strip(String text) {
        return text.replaceAll("\\[[0-9a-f]{3}\\]", "");
    }
}
