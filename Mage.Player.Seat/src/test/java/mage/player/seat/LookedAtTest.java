package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a seat looked at reaches its results (fullpod #93). Kinnan, Bonder
 * Prodigy's {5}{G}{U}: "Look at the top five cards of your library. You may
 * put a non-Human creature card from among them onto the battlefield. Put
 * the rest on the bottom of your library in a random order." With nothing
 * to take the engine asks nothing and bottoms the five, and its own
 * looked-at pile is gone before any result is built: the seat keeps the look
 * and its next results carry it — through the passes the server gives for
 * the person ({@code held}), until the seat's own next answer.
 */
public class LookedAtTest {

    private static final String KINNAN = "Kinnan, Bonder Prodigy";
    private static final List<String> LANDS = List.of("Forest", "Forest", "Forest", "Forest", "Forest", "Forest", "Island");
    /** No non-Human creature: Champion of the Parish is a Human Soldier. */
    private static final List<String> NOTHING_TO_TAKE = List.of("Plains", "Swamp", "Mountain", "Champion of the Parish", "Wastes");
    /** Two to choose from: with one, the engine takes it without asking which. */
    private static final List<String> A_BEAR = List.of("Plains", "Swamp", "Elvish Mystic", "Grizzly Bears", "Wastes");

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    private static List<String> board(List<String> more) {
        List<String> out = new ArrayList<>(LANDS);
        out.addAll(more);
        return out;
    }

    @Test(timeout = 240_000)
    public void aLookWithNoChoiceStaysUntilTheSeatsOwnAnswer() throws Exception {
        try (Fixture fx = new Fixture("look-none", false, true, f ->
                f.cheat(f.you, NOTHING_TO_TAKE, List.of(), board(List.of(KINNAN)), List.of()))) {
            Map<String, Object> main = fx.next("You");
            Assert.assertTrue("our main phase: " + main.get("context"), String.valueOf(main.get("context")).contains("YOUR_MAIN"));
            Assert.assertTrue("nothing looked at yet: " + main.get("looked_at"), Fixture.looked(main).isEmpty());
            fx.answer("You", Map.of("choice", Fixture.index(main, KINNAN, "activate")));

            // The ability resolved without a question: the first result after it.
            Map<String, Object> after = fx.next("You");
            Assert.assertEquals("a priority window, not a pick: " + after, "GAME_SELECT", after.get("action_type"));
            Assert.assertEquals("one pile: " + after.get("looked_at"), 1, Fixture.looked(after).size());
            Assert.assertEquals("the five looked at", new HashSet<>(NOTHING_TO_TAKE), new HashSet<>(Fixture.looked(after).get(0)));
            Assert.assertEquals(5, Fixture.looked(after).get(0).size());
            @SuppressWarnings("unchecked")
            Map<String, Object> pile = ((List<Map<String, Object>>) after.get("looked_at")).get(0);
            Assert.assertTrue("named for its source: " + pile.get("name"), String.valueOf(pile.get("name")).startsWith(KINNAN));
            @SuppressWarnings("unchecked")
            Map<String, Object> first = ((List<Map<String, Object>>) pile.get("cards")).get(0);
            Assert.assertNotNull("a card carries its short id: " + first, first.get("id"));
            Assert.assertEquals("the five are on the bottom, not in hand", 0, ((List<?>) GameHostTest.board(after).get("hand")).size());

            // A state poll is a result too; the other seat's never carries it.
            Assert.assertEquals(Fixture.looked(after), Fixture.looked(fx.host.state("You")));
            Assert.assertNull("another seat's look: " + fx.host.state("Opp").get("looked_at"), fx.host.state("Opp").get("looked_at"));

            // Not part of the game: a copy has none, and a serialized seat reads back with none.
            SeatPlayer seat = (SeatPlayer) fx.you;
            Assert.assertEquals(1, seat.looks().size());
            Assert.assertTrue("a copy carries no look", seat.copy().looks().isEmpty());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(seat);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                Assert.assertTrue("a seat read back carries no look", ((SeatPlayer) in.readObject()).looks().isEmpty());
            }

            // The server passes this window for the person: nobody has seen the pile yet.
            fx.answer("You", Map.of("choice", "no", "held", true));
            Map<String, Object> held = fx.next("You");
            Assert.assertNotEquals("the next question", after.get("game_seq"), held.get("game_seq"));
            Assert.assertEquals("still there after a held pass", Fixture.looked(after), Fixture.looked(held));

            // The seat's own answer: gone.
            fx.answer("You", Map.of("choice", "no"));
            Map<String, Object> answered = fx.next("You");
            Assert.assertNull("gone after the seat's own answer: " + answered.get("looked_at"), answered.get("looked_at"));
            Assert.assertNull(fx.host.state("You").get("looked_at"));
            Assert.assertTrue(seat.looks().isEmpty());

            Assert.assertFalse("the other seat was asked", fx.seen.get("Opp").isEmpty());
            for (Map<String, Object> r : fx.seen.get("Opp")) {
                Assert.assertNull("another seat's look on " + r.get("context"), r.get("looked_at"));
            }
        }
    }

    @Test(timeout = 240_000)
    public void aLookWithAChoiceIsOnePile() throws Exception {
        try (Fixture fx = new Fixture("look-pick", false, false, f ->
                f.cheat(f.you, A_BEAR, List.of(), board(List.of(KINNAN)), List.of()))) {
            Map<String, Object> main = fx.next("You");
            fx.answer("You", Map.of("choice", Fixture.index(main, KINNAN, "activate")));

            // "Put a non-Human creature card onto the battlefield?", asked over the five.
            Map<String, Object> ask = fx.next("You");
            Assert.assertEquals("the may: " + ask, "GAME_ASK", ask.get("action_type"));
            Assert.assertEquals("one pile, once: " + ask.get("looked_at"), 1, Fixture.looked(ask).size());
            Assert.assertEquals(new HashSet<>(A_BEAR), new HashSet<>(Fixture.looked(ask).get(0)));
            fx.answer("You", Map.of("choice", "yes"));

            Map<String, Object> pick = fx.next("You");
            Assert.assertEquals("the pick: " + pick, "GAME_TARGET", pick.get("action_type"));
            Set<String> offered = new HashSet<>();
            for (Map<String, Object> c : ScriptedSeat.choices(pick)) {
                offered.add(String.valueOf(c.get("name")));
            }
            Assert.assertEquals("only the non-Human creatures can be taken", Set.of("Grizzly Bears", "Elvish Mystic"), offered);
            Assert.assertNull("the may was the seat's own answer: " + pick.get("looked_at"), pick.get("looked_at"));

            fx.answer("You", Map.of("choice", Fixture.index(pick, "Grizzly Bears")));
            Map<String, Object> after = fx.next("You");
            Assert.assertNull("answered: " + after.get("looked_at"), after.get("looked_at"));
            Assert.assertTrue("the Bear is on the battlefield", GameHostTest.battlefieldHas(GameHostTest.board(after), "Grizzly Bears"));
        }
    }

    @Test(timeout = 240_000)
    public void anUndoneTurnsLookIsNotShownAgain() throws Exception {
        try (Fixture fx = new Fixture("look-undo", false, false, f ->
                f.cheat(f.you, NOTHING_TO_TAKE, List.of(), board(List.of(KINNAN)), List.of()))) {
            Map<String, Object> main = fx.next("You");
            fx.answer("You", Map.of("choice", Fixture.index(main, KINNAN, "activate")));
            Map<String, Object> after = fx.next("You");
            Assert.assertEquals(1, Fixture.looked(after).size());

            Assert.assertTrue("this turn can be restarted", fx.host.rollback("You", 0));
            Map<String, Object> again = fx.next("You");
            Assert.assertNull("the turn was undone: " + again.get("looked_at"), again.get("looked_at"));
            Assert.assertTrue(((SeatPlayer) fx.you).looks().isEmpty());
        }
    }
}
