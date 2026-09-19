package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which priority windows reach the seat with an empty stack. HumanPlayer
 * passes a step itself unless it is one of the user's phase stops, and the
 * engine's defaults are the mains and the combat steps; the seat adds Begin
 * Combat and End Turn on either turn, so the product's stops for them can
 * act. Upkeep and draw stay the engine's to pass.
 */
public class StopStepsTest {

    static final String BEARS = GameHostTest.BEARS;

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void beginCombatAndEndTurnWindowsReachTheSeatOnEitherTurn() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("stopsteps", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), false));
        ScriptedSeat script = new ScriptedSeat();
        Set<String> steps = new HashSet<>();
        try {
            host.start();
            for (int i = 0; i < 400; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                String context = String.valueOf(d.get("context"));
                if ("GAME_SELECT".equals(d.get("action_type")) && d.get("combat_phase") == null) {
                    // "T3 Combat/Begin Combat (You)" -> "Begin Combat (You)" / "(CPU)": the step and whose turn
                    String step = context.contains("/") ? context.substring(context.indexOf('/') + 1) : context;
                    boolean mine = step.contains("(You)");
                    steps.add(step.substring(0, step.indexOf(" (")) + (mine ? " · mine" : " · theirs"));
                }
                if (context.startsWith("T8 ") || context.startsWith("T9 ")) {
                    break;
                }
                Map<String, Object> answer = host.chooseAction("You", script.answer(d));
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        for (String want : List.of("Begin Combat · mine", "Begin Combat · theirs", "End Turn · mine", "End Turn · theirs",
                "Precombat Main · mine", "Declare Attackers · theirs")) {
            Assert.assertTrue(want + " asked; saw " + steps, steps.contains(want));
        }
        for (String skipped : List.of("Upkeep · mine", "Upkeep · theirs", "Draw · mine", "End Combat · mine")) {
            Assert.assertFalse(skipped + " stays the engine's to pass; saw " + steps, steps.contains(skipped));
        }
    }
}
