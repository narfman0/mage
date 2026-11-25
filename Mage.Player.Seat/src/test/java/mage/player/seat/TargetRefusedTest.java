package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A target the engine refuses comes back with the reason (fullpod #94).
 * Treason of Isengard ("Return up to one target instant or sorcery card from
 * your graveyard to the top of your library. Amass Orcs 2.") offers the
 * graveyard's instants by its filter; with an opponent's Ground Seal out the
 * pick is refused as it is made, XMage logs why, and asks the same question
 * again. That re-asked result carries the line as its {@code note}; the
 * question after it does not.
 */
public class TargetRefusedTest {

    private static final String TREASON = "Treason of Isengard";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 240_000)
    public void aGraveyardCardUnderGroundSealComesBackWithTheReason() throws Exception {
        try (Fixture fx = new Fixture("ground-seal", false, false, f -> {
            f.cheat(f.you, List.of(), List.of(TREASON), List.of("Island", "Island", "Island"), List.of("Opt"));
            f.cheat(f.opp, List.of(), List.of(), List.of("Ground Seal"), List.of());
        })) {
            Map<String, Object> main = fx.next("You");
            Assert.assertNull("a priority window has no note: " + main.get("note"), main.get("note"));
            fx.answer("You", Map.of("choice", Fixture.index(main, TREASON, "cast")));

            Map<String, Object> ask = fx.next("You");
            Assert.assertEquals("the target question: " + ask, "GAME_TARGET", ask.get("action_type"));
            Assert.assertNull("asked the first time, nothing was refused: " + ask.get("note"), ask.get("note"));
            fx.answer("You", Map.of("choice", Fixture.index(ask, "Opt")));

            Map<String, Object> again = fx.next("You");
            Assert.assertEquals("GAME_TARGET", again.get("action_type"));
            Assert.assertEquals("the same question", ask.get("message"), again.get("message"));
            Assert.assertNotEquals("asked again", ask.get("game_seq"), again.get("game_seq"));
            Fixture.index(again, "Opt"); // still offered, as the engine lists it
            Assert.assertEquals("Ground Seal: Cards in graveyards can't be the targets of spells or abilities.", again.get("note"));
            Assert.assertEquals("the same result on a second read", again.get("note"), fx.host.awaitDecision("You", 1_000).get("note"));

            // Picked again: refused again, said again.
            fx.answer("You", Map.of("choice", Fixture.index(again, "Opt")));
            Map<String, Object> third = fx.next("You");
            Assert.assertEquals(again.get("note"), third.get("note"));

            // Declined: the spell is cast with no target, and nothing after says "refused".
            fx.answer("You", Map.of("choice", "no"));
            Map<String, Object> after = fx.next("You");
            Assert.assertNull("the next question carries no note: " + after, after.get("note"));
            Assert.assertNotEquals("GAME_TARGET", after.get("action_type"));
        }
    }
}
