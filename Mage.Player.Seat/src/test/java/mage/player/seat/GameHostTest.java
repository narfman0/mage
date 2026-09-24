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
 * The harness: a whole game hosted in this JVM, driven through the same API
 * the product will use over the socket, by a scripted seat against
 * ComputerPlayer7. Asserts on the decisions as the product sees them.
 */
public class GameHostTest {

    private static final Logger LOG = Logger.getLogger(GameHostTest.class);
    static final String BEARS = "src/test/resources/decks/bears_and_forests.dck";

    @BeforeClass
    public static void loadCards() {
        long t0 = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
        LOG.info("cards scanned in " + (System.currentTimeMillis() - t0) + " ms");
    }

    @Test(timeout = 240_000)
    public void scriptedSeatPlaysBearsAgainstTheCpu() throws Exception {
        Path logDir = Files.createTempDirectory("seat-game");
        GameHost host = new GameHost(new GameHost.Config("harness", "duel", 7L, logDir.toString(),
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), false));
        ScriptedSeat script = new ScriptedSeat();
        long t0 = System.currentTimeMillis();
        host.start();
        long firstDecisionAt = 0;
        int maxTurn = 0;
        boolean sawOurCreature = false;
        boolean sawOurAttack = false;
        try {
            for (int i = 0; i < 400; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    LOG.info("game over: " + d.get("winner"));
                    break;
                }
                Assert.assertTrue("decision expected: " + d, Boolean.TRUE.equals(d.get("action_pending")));
                if (firstDecisionAt == 0) {
                    firstDecisionAt = System.currentTimeMillis();
                }
                String context = String.valueOf(d.get("context"));
                Assert.assertTrue("context: " + context, context.matches("^T\\d+ .*\\(.*\\).*"));
                Assert.assertNotNull("action_type", d.get("action_type"));
                Assert.assertNotNull("response_type", d.get("response_type"));
                Assert.assertNull("render error", d.get("error"));
                int turn = Integer.parseInt(context.substring(1, context.indexOf(' ')));
                maxTurn = Math.max(maxTurn, turn);
                Map<String, Object> board = board(d);
                Assert.assertTrue("our seat first on the board", Boolean.TRUE.equals(board.get("is_you")));
                // The seat tile's table state: land drops against the allowance on every
                // seat, always; nobody is the monarch, out, or designated in a bears duel.
                @SuppressWarnings("unchecked")
                Map<String, Object> drops = (Map<String, Object>) board.get("land_drops");
                Assert.assertEquals("one land a turn: " + board, 1, drops.get("per_turn"));
                Assert.assertTrue(String.valueOf(drops.get("used")).matches("[01]"));
                Assert.assertNull(board.get("monarch"));
                Assert.assertNull(board.get("out"));
                Assert.assertNull(board.get("designations"));
                if (battlefieldHas(board, "Grizzly Bears")) {
                    sawOurCreature = true;
                }
                if (d.get("already_attacking") != null || (d.get("combat") != null && "declare_blockers".equals(d.get("combat_phase")) == false && String.valueOf(d.get("combat")).contains("Grizzly Bears"))) {
                    sawOurAttack = true;
                }
                LOG.info("DECISION " + context + " " + d.get("action_type") + "/" + d.get("response_type") + " " + d.get("message")
                        + (d.get("combat_phase") != null ? " " + d.get("combat_phase") : "") + " choices=" + ScriptedSeat.choices(d).size());
                if (turn >= 8) {
                    host.end();
                    break;
                }
                Map<String, Object> answer = host.chooseAction("You", script.answer(d));
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        long elapsed = System.currentTimeMillis() - t0;
        LOG.info(String.format("first decision after %d ms; %d decisions in %d ms; reached turn %d; lands %d casts %d attacks %d blocks %d mana prompts %d",
                firstDecisionAt - t0, script.seen.size(), elapsed, maxTurn, script.lands, script.casts, script.attacks, script.blocks, script.manaPrompts));
        Assert.assertTrue("reached turn " + maxTurn, maxTurn >= 5);
        Assert.assertTrue("played lands", script.lands >= 2);
        Assert.assertTrue("cast a creature", script.casts >= 1);
        Assert.assertTrue("our creature reached the battlefield (mana was auto-paid)", sawOurCreature);
        Assert.assertEquals("no mana prompt reached the seat", 0, script.manaPrompts);
        Assert.assertTrue("declared an attack", script.attacks >= 1);
        Assert.assertTrue("our attack reached combat", sawOurAttack);
        Path record = logDir.resolve("server_game_events.jsonl");
        Assert.assertTrue("event record written", Files.exists(record));
        long decisions = Files.lines(record).filter(l -> l.contains("\"type\":\"decision\"") || l.contains("\"type\": \"decision\"")).count();
        Assert.assertTrue("decisions recorded: " + decisions, decisions >= 5);
    }

    @Test(timeout = 120_000)
    public void answersAreValidatedAgainstThePendingQuestion() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("validate", "duel", 3L, null,
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), false));
        try {
            Map<String, Object> none = host.chooseAction("You", Map.of("choice", "no"));
            Assert.assertEquals("no_pending_action", none.get("error_code"));
            host.start();
            Map<String, Object> d = host.awaitDecision("You", 120_000);
            Assert.assertEquals("GAME_TARGET", d.get("action_type")); // "Select a starting player"
            Map<String, Object> bad = host.chooseAction("You", Map.of("choice", "42"));
            Assert.assertEquals("index_out_of_range", bad.get("error_code"));
            Assert.assertEquals(true, bad.get("retryable"));
            Map<String, Object> wrongKind = host.chooseAction("You", Map.of("attackers", "all"));
            Assert.assertEquals("invalid_choice", wrongKind.get("error_code"));
            Map<String, Object> ok = host.chooseAction("You", Map.of("choice", "0"));
            Assert.assertEquals(true, ok.get("success"));
            Map<String, Object> again = host.awaitDecision("You", 120_000);
            Assert.assertEquals("GAME_ASK", again.get("action_type")); // mulligan
            Assert.assertTrue(String.valueOf(again.get("message")).toLowerCase().contains("mulligan"));
            Assert.assertNotNull("hand shown on the mulligan question", again.get("your_hand"));
            Map<String, Object> state = host.state("You");
            Assert.assertNotNull(state.get("board"));
            // A seat that concedes is `out` on the board it still sees.
            host.concede("You");
            for (int i = 0; i < 50 && !host.isOver(); i++) {
                Thread.sleep(100);
            }
            Map<String, Object> over = host.state("You");
            Assert.assertEquals(Boolean.TRUE, board(over).get("out"));
            // A real result names the winning seat by its name, not XMage's
            // sentence ("Player CPU is the winner"), and is never a crash.
            Assert.assertEquals(Boolean.TRUE, over.get("game_over"));
            Assert.assertEquals("CPU", over.get("winner"));
            Assert.assertNull(over.get("draw"));
            Assert.assertNull(over.get("engine_died"));
        } finally {
            host.end();
        }
    }

    /**
     * A game thread that dies (an OutOfMemoryError in GameState.copy, report
     * fa5cec13dd) is not a game that ended: no game_over, no winner, no draw
     * — XMage's getWinner() says "Game is a draw" for any game without a
     * winner, and that is what the player read. The result says engine_died
     * with the thread's cause.
     */
    @Test(timeout = 60_000)
    public void aDeadGameThreadIsACrashNotADraw() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("dies", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), false));
        try {
            host.launch(() -> {
                throw new OutOfMemoryError("Java heap space");
            });
            Map<String, Object> d = host.awaitDecision("You", 10_000);
            Assert.assertTrue(host.isOver());
            Assert.assertTrue(host.threadDied());
            Assert.assertEquals(Boolean.FALSE, d.get("action_pending"));
            Assert.assertEquals(Boolean.TRUE, d.get("engine_died"));
            Assert.assertNull("a crash is not a result: " + d, d.get("game_over"));
            Assert.assertNull(d.get("winner"));
            Assert.assertNull(d.get("draw"));
            Assert.assertNull("not a stall: " + d, d.get("timed_out"));
            Assert.assertEquals("game thread died: java.lang.OutOfMemoryError: Java heap space", d.get("error"));
            Assert.assertNull(host.winnerName());
        } finally {
            host.end();
        }
    }

    /**
     * A transforming double-faced card is two objects to the engine, and its
     * front face is keyed as playable beside the main card. The views know only
     * the main card, so a seat once saw "Unknown (3cc3a2fd) (activate)" next to
     * the real cast (a report, 2026-09-17). Now: one choice, by name, never an
     * Unknown.
     */
    @Test(timeout = 240_000)
    public void doubleFacedCardIsOnePlayableChoice() throws Exception {
        String deck = "src/test/resources/decks/dfc_forests.dck";
        GameHost host = new GameHost(new GameHost.Config("dfc", "duel", 4L, null,
                List.of(new GameHost.SeatSpec("You", "seat", deck, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), true));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        boolean sawRites = false;
        try {
            for (int i = 0; i < 300; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                for (Map<String, Object> c : choices) {
                    Assert.assertFalse("an Unknown choice: " + c + " in " + d.get("context"), String.valueOf(c.get("name")).startsWith("Unknown"));
                }
                long rites = "GAME_SELECT".equals(d.get("action_type"))
                        ? choices.stream().filter(c -> "Growing Rites of Itlimoc".equals(c.get("name")) && "cast".equals(c.get("action"))).count()
                        : 0;
                if (rites > 0) {
                    // Each copy in hand once — never once per face.
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> hand = (List<Map<String, Object>>) board(d).get("hand");
                    long inHand = hand.stream().filter(c -> "Growing Rites of Itlimoc".equals(c.get("name"))).count();
                    Assert.assertEquals("one choice per copy in hand: " + choices, inHand, rites);
                    sawRites = true;
                }
                String context = String.valueOf(d.get("context"));
                if (Integer.parseInt(context.substring(1, context.indexOf(' '))) >= 9) {
                    break;
                }
                Map<String, Object> answer = host.chooseAction("You", script.answer(d));
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Assert.assertTrue("Growing Rites was castable at some point (" + script.seen.size() + " decisions)", sawRites);
    }

    /**
     * Changing your mind after activating something (report 89e175786e:
     * "I clicked my Terramorphic Expanse to activate it and changed my mind,
     * then clicked Pass / Decline and the engine rejected the action"). On this
     * engine: the activation is offered, taken, and every question the
     * activation raises (a search, a picker) accepts "no" and the game goes on.
     */
    @Test(timeout = 240_000)
    public void activationCanBeBackedOutOf() throws Exception {
        String deck = "src/test/resources/decks/expanse_forests.dck";
        GameHost host = new GameHost(new GameHost.Config("expanse", "duel", 6L, null,
                List.of(new GameHost.SeatSpec("You", "seat", deck, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), true));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        boolean activated = false;
        List<String> afterActivation = new ArrayList<>();
        int turnAtActivation = -1;
        try {
            for (int i = 0; i < 300; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error: " + d.get("error"), d.get("error"));
                String context = String.valueOf(d.get("context"));
                int turn = Integer.parseInt(context.substring(1, context.indexOf(' ')));
                Map<String, Object> args;
                if (activated && afterActivation.size() < 4) {
                    // Everything the activation raises: decline it.
                    afterActivation.add(d.get("action_type") + "/" + d.get("response_type") + " " + d.get("message"));
                    args = Map.of("choice", "no");
                    if (turn > turnAtActivation + 1) {
                        break;
                    }
                } else if (activated && turn > turnAtActivation + 1) {
                    break;
                } else {
                    Map<String, Object> expanse = ScriptedSeat.choices(d).stream()
                            .filter(c -> "Terramorphic Expanse".equals(c.get("name")) && "activate".equals(c.get("action"))).findFirst().orElse(null);
                    Map<String, Object> asLand = ScriptedSeat.choices(d).stream()
                            .filter(c -> "Terramorphic Expanse".equals(c.get("name")) && "land".equals(c.get("action"))).findFirst().orElse(null);
                    if (expanse != null) {
                        activated = true;
                        turnAtActivation = turn;
                        args = Map.of("choice", String.valueOf(expanse.get("index")));
                    } else if (asLand != null) {
                        args = Map.of("choice", String.valueOf(asLand.get("index"))); // get it onto the battlefield first
                    } else {
                        args = script.answer(d);
                    }
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Assert.assertTrue("the activation was offered and taken (" + script.seen.size() + " decisions)", activated);
        LOG.info("after activating Terramorphic Expanse and declining: " + afterActivation);
        Assert.assertFalse("questions followed the activation", afterActivation.isEmpty());
    }

    /**
     * A typed library search only offers the legal subset (report 7b2f2ef77e:
     * Worldly Tutor — "search your library for a creature card" — offered
     * every creature AND every land). HumanPlayer.choose(Cards, TargetCard, ...)
     * only ever puts the legal subset in options["possibleTargets"]; it never
     * fills e.getTargets(), so DecisionRenderer.target() used to fall back to
     * "every card in the zone is legal".
     */
    @Test(timeout = 240_000)
    public void worldlyTutorOnlyOffersCreatures() throws Exception {
        String deck = "src/test/resources/decks/worldly_tutor_forests.dck";
        GameHost host = new GameHost(new GameHost.Config("tutor", "duel", 1L, null,
                List.of(new GameHost.SeatSpec("You", "seat", deck, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), true));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        boolean sawSearchPrompt = false;
        try {
            for (int i = 0; i < 300; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                boolean isLibrarySearch = "GAME_TARGET".equals(d.get("action_type"))
                        && choices.stream().anyMatch(c -> "Grizzly Bears".equals(c.get("name")) || "Forest".equals(c.get("name")));
                if (isLibrarySearch) {
                    sawSearchPrompt = true;
                    Assert.assertTrue("at least one creature offered: " + choices, choices.stream().anyMatch(c -> "Grizzly Bears".equals(c.get("name"))));
                    Assert.assertFalse("a land offered for a creature search: " + choices, choices.stream().anyMatch(c -> "Forest".equals(c.get("name"))));
                    break;
                }
                Map<String, Object> tutor = choices.stream()
                        .filter(c -> "Worldly Tutor".equals(c.get("name")) && "cast".equals(c.get("action"))).findFirst().orElse(null);
                Map<String, Object> args = tutor != null ? Map.of("choice", String.valueOf(tutor.get("index"))) : script.answer(d);
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Assert.assertTrue("reached Worldly Tutor's search prompt", sawSearchPrompt);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> board(Map<String, Object> d) {
        return ((List<Map<String, Object>>) d.get("board")).get(0);
    }

    @SuppressWarnings("unchecked")
    static boolean battlefieldHas(Map<String, Object> player, String name) {
        Object bf = player.get("battlefield");
        if (!(bf instanceof List<?> l)) {
            return false;
        }
        for (Object o : l) {
            if (name.equals(((Map<String, Object>) o).get("name"))) {
                return true;
            }
        }
        return false;
    }
}
