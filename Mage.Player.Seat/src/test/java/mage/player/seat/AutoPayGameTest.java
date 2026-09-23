package mage.player.seat;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.players.Player;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The auto-payer through the real engine: a hand-built board, one spell in
 * hand, and what happens when the seat casts it — paid without a word from
 * the right sources, or the mana prompt reaching the seat because the choice
 * is the player's. The clean-source rule is on the cards themselves here
 * (Ancient Tomb's damage, Crystal Vein's sacrifice, a creature, conditional
 * mana), which {@link AutoPayTest} cannot see.
 */
public class AutoPayGameTest {

    private static final Logger LOG = Logger.getLogger(AutoPayGameTest.class);
    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    /** What one cast produced: the mana prompt count, and the board after. */
    private record Outcome(int manaPrompts, boolean cast, Set<String> tapped, Set<String> untapped, int life, List<String> log, List<String> prompts) {
    }

    /**
     * One scenario: {@code battlefield} under our control, {@code spell} in
     * hand, seven uncastable cards on top of the library so the opening hand
     * adds nothing playable. We choose to go first, keep, and at our first
     * main phase cast the spell; a mana prompt is answered with Cancel (what
     * the product's pilot does, and what a person's Cancel does).
     */
    private Outcome play(String spell, String... battlefield) throws Exception {
        return play(spell, List.of(), battlefield);
    }

    /**
     * {@code picks}: the sources to answer each mana prompt with, in order,
     * by name (a browser's multi-select sends its picks one prompt at a
     * time); past the list a prompt is cancelled. Each prompt's message and
     * offered sources go to {@code prompts}.
     */
    private Outcome play(String spell, List<String> picks, String... battlefield) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("autopay", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), true));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<PutToBattlefieldInfo> perms = new ArrayList<>();
        for (String name : battlefield) {
            perms.add(new PutToBattlefieldInfo(card(name), false));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card(spell)), perms, List.of(), List.of(), List.of());
        int manaPrompts = 0;
        boolean cast = false;
        Set<String> tapped = new HashSet<>();
        Set<String> untapped = new HashSet<>();
        int life = -1;
        List<String> log = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        host.start();
        try {
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                log.add(d.get("context") + " " + type + " " + message);
                if (cast) {
                    // The decision after the cast: the mana prompt (the choice is the
                    // player's), or the next priority with the payment made.
                    if ("GAME_PLAY_MANA".equals(type) || "GAME_PLAY_XMANA".equals(type)) {
                        List<String> offered = new ArrayList<>();
                        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                            // "Forest#p3={G}{G}": the id a pick is answered with, and
                            // what the source makes once the board has its say.
                            Object produces = c.get("produces");
                            offered.add(c.get("name") + "#" + c.get("id") + (produces != null ? "=" + produces : ""));
                        }
                        prompts.add(message + " | " + String.join(", ", offered));
                        String pick = manaPrompts < picks.size() ? picks.get(manaPrompts) : null;
                        manaPrompts++;
                        String idx = null;
                        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                            if (pick != null && pick.equals(c.get("name"))) {
                                idx = String.valueOf(c.get("index"));
                                break;
                            }
                        }
                        Map<String, Object> answered = host.chooseAction("You", Map.of("choice", idx != null ? idx : "no"));
                        Assert.assertTrue("answer rejected: " + answered + " for " + d, Boolean.TRUE.equals(answered.get("success")));
                        continue;
                    }
                    Map<String, Object> board = GameHostTest.board(d);
                    for (Object o : (List<?>) board.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        // The seat passes the window after its own cast, so the next
                        // decision may be past the spell's resolution: the spell itself
                        // (a creature, untapped) is not one of the sources under test.
                        if (spell.equals(String.valueOf(perm.get("name")))) {
                            continue;
                        }
                        (Boolean.TRUE.equals(perm.get("tapped")) ? tapped : untapped).add(String.valueOf(perm.get("name")));
                    }
                    life = ((Number) board.get("life")).intValue();
                    break;
                }
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_PLAY_MANA".equals(type) || "GAME_PLAY_XMANA".equals(type)) {
                    manaPrompts++;
                    args = Map.of("choice", "no");
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d, spell) != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(d, spell));
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Outcome out = new Outcome(manaPrompts, cast, tapped, untapped, life, log, prompts);
        LOG.info(spell + " with " + List.of(battlefield) + " -> " + out);
        return out;
    }

    private static String indexOfYou(Map<String, Object> d) {
        List<Map<String, Object>> choices = ScriptedSeat.choices(d);
        for (int i = 0; i < choices.size(); i++) {
            if (Boolean.TRUE.equals(choices.get(i).get("is_you"))) {
                return String.valueOf(i);
            }
        }
        return "0";
    }

    private static String castIndex(Map<String, Object> d, String spell) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("cast".equals(c.get("action")) && spell.equals(c.get("name"))) {
                return String.valueOf(c.get("index"));
            }
        }
        return null;
    }

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name, true);
        Assert.assertNotNull("card: " + name, info);
        return info.createCard();
    }

    private static void paid(Outcome o, String... tapped) {
        Assert.assertTrue("cast offered and taken: " + o, o.cast());
        Assert.assertEquals("no mana prompt: " + o, 0, o.manaPrompts());
        Assert.assertEquals("tapped: " + o, Set.of(tapped), o.tapped());
    }

    private static void asked(Outcome o) {
        Assert.assertTrue("cast offered and taken: " + o, o.cast());
        Assert.assertTrue("the mana prompt reached the seat: " + o, o.manaPrompts() >= 1);
    }

    @Test(timeout = 240_000)
    public void whichForestDoesNotMatter() throws Exception {
        Outcome o = play("Llanowar Elves", "Forest", "Forest", "Forest");
        paid(o, "Forest");
        Assert.assertTrue("two Forests untapped: " + o, o.untapped().contains("Forest"));
    }

    @Test(timeout = 240_000)
    public void forestOrIslandForOneGenericIsAsked() throws Exception {
        asked(play("Sol Ring", "Forest", "Island"));
    }

    @Test(timeout = 240_000)
    public void tappingOutIsAutomatic() throws Exception {
        paid(play("Mind Stone", "Forest", "Island"), "Forest", "Island");
    }

    @Test(timeout = 240_000)
    public void ancientTombIsNeverTappedForYouWhenAForestCan() throws Exception {
        Outcome o = play("Sol Ring", "Ancient Tomb", "Forest");
        paid(o, "Forest");
        Assert.assertEquals("no damage taken: " + o, 20, o.life());
    }

    @Test(timeout = 240_000)
    public void ancientTombAloneIsAsked() throws Exception {
        asked(play("Sol Ring", "Ancient Tomb"));
    }

    @Test(timeout = 240_000)
    public void aRockPayingExactlyIsUsedAndABasicKept() throws Exception {
        paid(play("Mind Stone", "Sol Ring", "Forest"), "Sol Ring");
    }

    @Test(timeout = 240_000)
    public void aRockOrABasicForOneGenericIsAsked() throws Exception {
        asked(play("Sol Ring", "Sol Ring", "Forest"));
    }

    @Test(timeout = 240_000)
    public void aManaCreatureIsNeverTappedForYou() throws Exception {
        Outcome o = play("Llanowar Elves", "Llanowar Elves", "Forest");
        paid(o, "Forest");
    }

    @Test(timeout = 240_000)
    public void conditionalManaIsNotTheEnginesToSpend() throws Exception {
        // Ancient Ziggurat's mana is for creature spells only: not a clean source, so the Forest is the one plan.
        paid(play("Llanowar Elves", "Ancient Ziggurat", "Forest"), "Forest");
    }

    @Test(timeout = 240_000)
    public void crystalVeinPaysWithItsPlainAbilityAndStays() throws Exception {
        Outcome o = play("Mind Stone", "Crystal Vein", "Forest");
        paid(o, "Crystal Vein", "Forest");
        Assert.assertTrue("the Vein was not sacrificed: " + o, o.tapped().contains("Crystal Vein"));
    }

    /**
     * What a browser's multi-select relies on: after a source is picked in
     * the prompt, the engine asks again for what is still owed, offering the
     * untapped sources under the same ids, until the cost is met — and stops
     * asking the moment the rest is no longer the player's choice.
     */
    @Test(timeout = 240_000)
    public void aPromptAnsweredWithASourceComesBackForTheRest() throws Exception {
        Outcome o = play("Grizzly Bears", List.of("Forest", "Island"), "Forest", "Island", "Swamp");
        Assert.assertTrue(o.cast());
        Assert.assertEquals("two prompts: " + o.prompts(), 2, o.manaPrompts());
        Assert.assertTrue("the first asks for the whole cost: " + o.prompts(), o.prompts().get(0).startsWith("Pay {1}{G}"));
        Assert.assertTrue("the second asks for what is left: " + o.prompts(), o.prompts().get(1).startsWith("Pay {1}"));
        Assert.assertFalse("the tapped Forest is no longer offered: " + o.prompts(), o.prompts().get(1).contains("Forest#"));
        Assert.assertTrue("the Island is offered under its id again: " + o.prompts(), o.prompts().get(1).contains("Island#"));
        Assert.assertEquals(Set.of("Forest", "Island"), o.tapped());
        Assert.assertEquals(Set.of("Swamp"), o.untapped());
        // Forest first, then the rest is forced (only the Island is left for {1}{G}'s {1}... no: {G} paid, {1} from Island or Swamp — asked).
        // Island first for {1}{G}: the {G} left has one clean source, so the engine pays it without asking again.
        Outcome forced = play("Grizzly Bears", List.of("Island"), "Forest", "Island", "Swamp");
        Assert.assertEquals("one prompt, the rest was forced: " + forced.prompts(), 1, forced.manaPrompts());
        Assert.assertEquals(Set.of("Forest", "Island"), forced.tapped());
    }

    /**
     * A doubler is not in the printed ability: Mana Reflection replaces the
     * {@code TAPPED_FOR_MANA} event, so the one Forest makes {G}{G} and pays
     * {1}{G} by itself — no second land, and nothing to ask (report
     * 3b9a9dfa92).
     */
    @Test(timeout = 240_000)
    public void aDoublerMakesOneLandPayTwoMana() throws Exception {
        paid(play("Elvish Visionary", "Mana Reflection", "Forest"), "Forest");
    }

    /**
     * And the prompt says so, for a client that would otherwise count the
     * rules text: each source carries what tapping it adds.
     */
    @Test(timeout = 240_000)
    public void aPromptSaysWhatASourceReallyMakes() throws Exception {
        Outcome o = play("Sol Ring", "Mana Reflection", "Forest", "Island");
        asked(o);
        String prompt = o.prompts().get(0);
        Assert.assertTrue("the Forest makes two green: " + prompt, prompt.contains("={G}{G}"));
        Assert.assertTrue("the Island makes two blue: " + prompt, prompt.contains("={U}{U}"));
    }

    @Test(timeout = 240_000)
    public void aDualIsKeptUpWhenABasicCanPay() throws Exception {
        paid(play("Llanowar Elves", "Breeding Pool", "Forest"), "Forest");
        paid(play("Grizzly Bears", "Breeding Pool", "Forest", "Island"), "Forest", "Island");
    }

    /** A mana prompt as the seat saw it: the message, and the offered choices by name. */
    private record Prompt(String message, List<String> offered) {
    }

    /**
     * Tap your own mana first (docs/board-ui.md): at priority, float
     * {@code sources} one per window (they are offered as {@code action:
     * "mana"}), then cast {@code spell} and return the first mana prompt.
     */
    private Prompt floatThenCast(String spell, List<String> sources, String... battlefield) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("autopay-pool", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), true));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<PutToBattlefieldInfo> perms = new ArrayList<>();
        for (String name : battlefield) {
            perms.add(new PutToBattlefieldInfo(card(name), false));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card(spell)), perms, List.of(), List.of(), List.of());
        List<String> toFloat = new ArrayList<>(sources);
        host.start();
        try {
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game ended before the mana prompt: " + d, Boolean.TRUE.equals(d.get("game_over")));
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                if ("GAME_PLAY_MANA".equals(type) || "GAME_PLAY_XMANA".equals(type)) {
                    List<String> offered = new ArrayList<>();
                    for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                        offered.add(String.valueOf(c.get("name")));
                    }
                    return new Prompt(String.valueOf(d.get("message")), offered);
                }
                boolean priority = "GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null;
                Map<String, Object> args = null;
                if (priority && !toFloat.isEmpty()) {
                    String want = toFloat.get(0);
                    for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                        if ("mana".equals(c.get("action")) && want.equals(c.get("name"))) {
                            args = Map.of("choice", String.valueOf(c.get("index")));
                            toFloat.remove(0);
                            break;
                        }
                    }
                }
                String castIdx = args == null && priority && toFloat.isEmpty() ? castIndex(d, spell) : null;
                if (args == null && castIdx != null) {
                    args = Map.of("choice", castIdx);
                } else if (args == null && "GAME_TARGET".equals(type) && String.valueOf(d.get("message")).contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if (args == null) {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            throw new AssertionError("never reached the mana prompt");
        } finally {
            host.end();
        }
    }

    /**
     * Like {@link #floatThenCast}, but continues past the first mana
     * prompt: {@code picks} answers each one in order by name (a pool
     * button or a tap-source), same as {@link #play}'s multi-step picks,
     * so a scenario can float more than gets spent and check what happens
     * to the rest of the cost.
     */
    private Outcome floatThenPlay(String spell, List<String> floatFirst, List<String> picks, String... battlefield) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("autopay-pool-rest", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), true));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<PutToBattlefieldInfo> perms = new ArrayList<>();
        for (String name : battlefield) {
            perms.add(new PutToBattlefieldInfo(card(name), false));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card(spell)), perms, List.of(), List.of(), List.of());
        List<String> toFloat = new ArrayList<>(floatFirst);
        int manaPrompts = 0;
        boolean cast = false;
        Set<String> tapped = new HashSet<>();
        Set<String> untapped = new HashSet<>();
        int life = -1;
        List<String> log = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        host.start();
        try {
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                log.add(d.get("context") + " " + type + " " + message);
                if (cast) {
                    // The decision after the cast: the mana prompt (the choice is the
                    // player's), or the next priority with the payment made.
                    if ("GAME_PLAY_MANA".equals(type) || "GAME_PLAY_XMANA".equals(type)) {
                        List<String> offered = new ArrayList<>();
                        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                            // "Forest#p3={G}{G}": the id a pick is answered with, and
                            // what the source makes once the board has its say.
                            Object produces = c.get("produces");
                            offered.add(c.get("name") + "#" + c.get("id") + (produces != null ? "=" + produces : ""));
                        }
                        prompts.add(message + " | " + String.join(", ", offered));
                        String pick = manaPrompts < picks.size() ? picks.get(manaPrompts) : null;
                        manaPrompts++;
                        String idx = null;
                        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                            if (pick != null && pick.equals(c.get("name"))) {
                                idx = String.valueOf(c.get("index"));
                                break;
                            }
                        }
                        Map<String, Object> answered = host.chooseAction("You", Map.of("choice", idx != null ? idx : "no"));
                        Assert.assertTrue("answer rejected: " + answered + " for " + d, Boolean.TRUE.equals(answered.get("success")));
                        continue;
                    }
                    Map<String, Object> board = GameHostTest.board(d);
                    for (Object o : (List<?>) board.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        // The seat passes the window after its own cast, so the next
                        // decision may be past the spell's resolution: the spell itself
                        // (a creature, untapped) is not one of the sources under test.
                        if (spell.equals(String.valueOf(perm.get("name")))) {
                            continue;
                        }
                        (Boolean.TRUE.equals(perm.get("tapped")) ? tapped : untapped).add(String.valueOf(perm.get("name")));
                    }
                    life = ((Number) board.get("life")).intValue();
                    break;
                }
                boolean priority = "GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null;
                Map<String, Object> args = null;
                if (priority && !toFloat.isEmpty()) {
                    String want = toFloat.get(0);
                    for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                        if ("mana".equals(c.get("action")) && want.equals(c.get("name"))) {
                            args = Map.of("choice", String.valueOf(c.get("index")));
                            toFloat.remove(0);
                            break;
                        }
                    }
                }
                String castIdx = args == null && priority && toFloat.isEmpty() ? castIndex(d, spell) : null;
                if (args == null && castIdx != null) {
                    cast = true;
                    args = Map.of("choice", castIdx);
                } else if (args == null && "GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if (args == null) {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Outcome out = new Outcome(manaPrompts, cast, tapped, untapped, life, log, prompts);
        LOG.info(spell + " floating " + floatFirst + " with " + List.of(battlefield) + " -> " + out);
        return out;
    }

    /**
     * Owe a cost with both a coloured pip and a generic one: every floating
     * colour should be offered back, not only the one an explicit pip in
     * what's left happens to name. Report a9bddc7747 (2026-09-17): {G} and
     * {B} floating, {1}{G} owed — only "Green" (the explicit pip) was
     * offered; the floating black had no button, so nothing could spend it
     * on the {1}.
     */
    @Test(timeout = 240_000)
    public void poolManaOffersEveryFloatingColourNotJustTheExplicitPip() throws Exception {
        Prompt p = floatThenCast("Grizzly Bears", List.of("Forest", "Swamp"), "Forest", "Swamp");
        Assert.assertTrue("owed {1}{G}: " + p, p.message().startsWith("Pay {1}{G}"));
        Assert.assertTrue("the explicit pip's colour is offered: " + p, p.offered().contains("Green"));
        Assert.assertTrue("the other floating colour is offered too, for the generic {1}: " + p, p.offered().contains("Black"));
    }

    /** A twobrid pip ({2/W}) takes any colour too, and its W matches the colour scan: floating red must still get a button. */
    @Test(timeout = 240_000)
    public void poolManaOffersAFloatingColourForATwobridPip() throws Exception {
        Prompt p = floatThenCast("Spectral Procession", List.of("Mountain"), "Mountain", "Plains", "Plains", "Plains");
        Assert.assertTrue("owed {2/W}s: " + p, p.message().startsWith("Pay {2/W}"));
        Assert.assertTrue("the floating red is offered: " + p, p.offered().contains("Red"));
    }

    /**
     * More floats than gets spent, plus a clean land that could alone cover
     * what's left: the leftover float must still get offered before AutoPay
     * taps that land for you. Report ed184e0607 (2026-09-18): {G} and {B}
     * floated (Forest, Swamp tapped for mana), only the {G} pool button was
     * spent on a {1}{G} cost, and the {1} left over was silently paid by
     * tapping an untapped Island — the still-floating {B} was never offered
     * again, and AutoPay's plan never looks at the pool at all.
     */
    @Test(timeout = 240_000)
    public void leftoverFloatIsOfferedBeforeTheRestIsTappedForYou() throws Exception {
        Outcome o = floatThenPlay("Grizzly Bears", List.of("Forest", "Swamp"), List.of("Green", "Black"), "Forest", "Swamp", "Island");
        Assert.assertTrue("cast: " + o, o.cast());
        Assert.assertEquals("two mana prompts: the {G} pip, then the {1} the float could still pay: " + o.prompts(), 2, o.manaPrompts());
        Assert.assertTrue("Island stays untapped, paid from the float instead: " + o, o.untapped().contains("Island"));
        Assert.assertFalse("Island was not tapped for you: " + o, o.tapped().contains("Island"));
    }
}
