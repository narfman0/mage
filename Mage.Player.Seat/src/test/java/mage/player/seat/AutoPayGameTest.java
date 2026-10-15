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
        return play(true, spell, picks, battlefield);
    }

    /** {@code person}: a person's seat (mana sources offered, an ambiguous payment asked); off, a pilot's. */
    private Outcome play(boolean person, String spell, List<String> picks, String... battlefield) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("autopay", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), person));
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

    /**
     * A pilot taps a basic into a Signet (fullpod issue #31): Kaalia of the
     * Vast off a Boros Signet, a Plains and two Swamps, which no tap-only
     * plan can pay (only the Signet makes red). The pilot's driver cancels
     * every mana prompt, so before this the cast was always cancelled.
     */
    @Test(timeout = 240_000)
    public void aPilotTapsABasicIntoASignetForItsFourDrop() throws Exception {
        Outcome o = play(false, "Kaalia of the Vast", List.of(), "Boros Signet", "Plains", "Swamp", "Swamp");
        paid(o, "Boros Signet", "Plains", "Swamp");
        Assert.assertTrue("everything tapped: " + o, o.untapped().isEmpty());
    }

    /**
     * {W} from a Signet and a Swamp: the Swamp goes into the Signet, {W}
     * pays, {R} floats. A pilot passing then is never asked whether to lose
     * it (a "no" puts it back at the same priority, to be asked again: the
     * golden recorder looped there).
     */
    @Test(timeout = 240_000)
    public void aPilotPassesWithASignetsSpareManaUnasked() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("autopay-float", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), false));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Savannah Lions")),
                List.of(new PutToBattlefieldInfo(card("Boros Signet"), false), new PutToBattlefieldInfo(card("Swamp"), false)),
                List.of(), List.of(), List.of());
        host.start();
        try {
            boolean cast = false;
            int passesAfter = 0;
            for (int i = 0; i < 60 && passesAfter < 3; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                Assert.assertFalse("game over too soon: " + d, Boolean.TRUE.equals(d.get("game_over")));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                Assert.assertFalse("asked about the floating mana: " + d, message.contains("mana pool"));
                Assert.assertNotEquals("no mana prompt: " + d, "GAME_PLAY_MANA", type);
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if (!cast && "GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d, "Savannah Lions") != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(d, "Savannah Lions"));
                } else {
                    if (cast) {
                        passesAfter++;
                    }
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
            Assert.assertTrue("cast", cast);
            Assert.assertNotNull("the Lions resolved", game.getBattlefield().getAllActivePermanents(you.getId()).stream()
                    .filter(p -> "Savannah Lions".equals(p.getName())).findFirst().orElse(null));
        } finally {
            host.end();
        }
    }

    /** A pilot keeps a land up rather than a Signet nothing could pay for. */
    @Test(timeout = 240_000)
    public void aPilotKeepsALandUpRatherThanAnUnpayableSignet() throws Exception {
        Outcome o = play(false, "Mind Stone", List.of(), "Boros Signet", "Plains", "Swamp");
        Assert.assertTrue("cast: " + o, o.cast());
        Assert.assertEquals("no mana prompt: " + o, 0, o.manaPrompts());
        Assert.assertTrue("the Signet tapped: " + o, o.tapped().contains("Boros Signet"));
        Assert.assertEquals("one land kept up: " + o, 1, o.untapped().size());
    }

    /** A person taps their own Signet: the engine never plans one for them. */
    @Test(timeout = 240_000)
    public void aPersonIsAskedRatherThanHavingASignetTappedForThem() throws Exception {
        asked(play("Kaalia of the Vast", "Boros Signet", "Plains", "Swamp", "Swamp"));
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

    /**
     * Restricted mana that can't pay this spell gets no pool button: a click
     * on it paid nothing and the same prompt came back, forever (#52: Mox
     * Jasper's red for Nogi, a non-Dragon). Mishra's Workshop's {C}{C}{C}
     * is for artifact spells only, and Grizzly Bears is not one.
     */
    @Test(timeout = 240_000)
    public void poolManaOffersNoRestrictedManaThatCantPayThisSpell() throws Exception {
        // Swamp or Mountain for the {1}: an ambiguous payment, so the person is asked.
        Prompt p = floatThenCast("Grizzly Bears", List.of("Mishra's Workshop"), "Mishra's Workshop", "Forest", "Swamp", "Mountain");
        Assert.assertTrue("owed {1}{G}: " + p, p.message().startsWith("Pay {1}{G}"));
        Assert.assertFalse("the Workshop's colourless can't pay a creature: " + p, p.offered().contains("Colorless"));
        Assert.assertTrue("the lands still can: " + p, p.offered().contains("Forest"));
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

    /**
     * Mana kept until end of turn is not "lost" when a window passes, so the
     * seat is not asked to confirm losing it (fullpod #53: Savage Ventmaw's
     * mana, asked at every pass of the attack). Sakura-Tribe Springcaller's
     * {G} arrives at upkeep with the same "you don't lose this mana as steps
     * and phases end"; every window of the turn before the end phase passes
     * without the question.
     */
    @Test(timeout = 240_000)
    public void manaKeptUntilEndOfTurnIsNotLostOnAPass() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("autopay-keep", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), true));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card("Colossal Dreadmaw")),
                List.of(new PutToBattlefieldInfo(card("Sakura-Tribe Springcaller"), false)), List.of(), List.of(), List.of());
        List<String> log = new ArrayList<>();
        boolean floated = false;
        host.start();
        try {
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error", d.get("error"));
                String context = String.valueOf(d.get("context"));
                String message = String.valueOf(d.get("message"));
                log.add(context + " " + d.get("action_type") + " " + message);
                if (!context.startsWith("T1 ") || context.startsWith("T1 End")) {
                    break; // the end phase empties it, as the rules say
                }
                Assert.assertFalse("asked to lose mana that is kept until end of turn: " + log, message.contains("will be lost"));
                if (d.get("board") != null && GameHostTest.board(d).get("mana_pool") != null) {
                    floated = true;
                }
                Map<String, Object> args = "GAME_TARGET".equals(d.get("action_type")) && message.contains("starting player")
                        ? Map.of("choice", indexOfYou(d))
                        : Map.of("choice", "no");
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Assert.assertTrue("Springcaller's {G} floated on turn 1: " + log, floated);
    }

    /**
     * A payment answered as a whole (fullpod #84): what the first mana prompt
     * suggested (by source name, a pool colour once per pip), the `pay`
     * answer's fate, how many mana prompts followed it, and how many other
     * questions (a colour to make) came between the cast and the next window.
     */
    private record PlanOutcome(Outcome outcome, List<String> suggested, int promptsAfter, int otherPrompts) {
    }

    /**
     * Float {@code floatFirst} at priority, cast {@code spell}, and answer the
     * first mana prompt with {@code pay=…}: the suggestion when {@code payWith}
     * is null, else these names (a pool colour by its name; "Breeding
     * Pool={U}" the ability of a source that makes blue). Every mana
     * prompt after it is cancelled and counted; a list question in between
     * (which colour) takes its first entry and is counted.
     */
    private PlanOutcome playPlan(String spell, List<String> floatFirst, List<String> payWith, String... battlefield) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("autopay-plan", "duel", 5L, null,
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
        boolean cast = false;
        boolean planned = false;
        List<String> suggested = new ArrayList<>();
        int promptsAfter = 0;
        int otherPrompts = 0;
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
                boolean priority = "GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null;
                boolean mana = "GAME_PLAY_MANA".equals(type) || "GAME_PLAY_XMANA".equals(type);
                if (cast && mana && !planned) {
                    planned = true;
                    List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                    List<String> offered = new ArrayList<>();
                    for (Map<String, Object> c : choices) {
                        offered.add(c.get("name") + "#" + c.get("id"));
                    }
                    prompts.add(message + " | " + String.join(", ", offered));
                    List<Object> indices = new ArrayList<>();
                    Object raw = d.get("suggested");
                    if (raw instanceof List<?> list) {
                        for (Object o : list) {
                            int at = ((Number) o).intValue();
                            suggested.add(String.valueOf(choices.get(at).get("name")));
                            indices.add(at);
                        }
                    }
                    if (payWith != null) {
                        indices.clear();
                        for (String name : payWith) {
                            // "Breeding Pool={U}": the source's ability that makes blue
                            // (a shock land is two mana abilities, one per colour).
                            String produce = name.contains("=") ? name.substring(name.indexOf('=') + 1) : null;
                            String want = produce == null ? name : name.substring(0, name.indexOf('='));
                            Integer at = null;
                            for (Map<String, Object> c : choices) {
                                if (want.equals(c.get("name")) && (produce == null || String.valueOf(c.get("ability")).contains(produce) || String.valueOf(c.get("produces")).contains(produce))) {
                                    at = ((Number) c.get("index")).intValue();
                                    break;
                                }
                            }
                            Assert.assertNotNull(name + " is offered: " + prompts, at);
                            indices.add(at);
                        }
                    }
                    Map<String, Object> answered = host.chooseAction("You", Map.of("pay", indices));
                    Assert.assertTrue("pay rejected: " + answered + " for " + d, Boolean.TRUE.equals(answered.get("success")));
                    continue;
                }
                if (cast && mana) {
                    promptsAfter++;
                    prompts.add(message);
                    Map<String, Object> answered = host.chooseAction("You", Map.of("choice", "no"));
                    Assert.assertTrue("cancel rejected: " + answered, Boolean.TRUE.equals(answered.get("success")));
                    continue;
                }
                if (cast && !priority) {
                    otherPrompts++;
                    log.add("  other: " + message);
                    Map<String, Object> answered = host.chooseAction("You", Map.of("choice", "GAME_CHOOSE_CHOICE".equals(type) ? "0" : "no"));
                    Assert.assertTrue("answer rejected: " + answered + " for " + d, Boolean.TRUE.equals(answered.get("success")));
                    continue;
                }
                if (cast) {
                    Map<String, Object> board = GameHostTest.board(d);
                    for (Object o : (List<?>) board.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        if (spell.equals(String.valueOf(perm.get("name")))) {
                            continue;
                        }
                        (Boolean.TRUE.equals(perm.get("tapped")) ? tapped : untapped).add(String.valueOf(perm.get("name")));
                    }
                    life = ((Number) board.get("life")).intValue();
                    break;
                }
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
        Assert.assertTrue("the mana prompt reached the seat: " + log, planned);
        PlanOutcome out = new PlanOutcome(new Outcome(1 + promptsAfter, cast, tapped, untapped, life, log, prompts), suggested, promptsAfter, otherPrompts);
        LOG.info(spell + " plan with " + List.of(battlefield) + " -> " + out);
        return out;
    }

    /** Forest or Island for Sol Ring's {1}: the prompt suggests one land, and paying with the suggestion taps just that. */
    @Test(timeout = 240_000)
    public void aSuggestedPaymentIsOneAnswer() throws Exception {
        PlanOutcome o = playPlan("Sol Ring", List.of(), null, "Forest", "Island");
        Assert.assertEquals("one land suggested: " + o, 1, o.suggested().size());
        Assert.assertTrue("cast: " + o, o.outcome().cast());
        Assert.assertEquals("no further prompt: " + o, 0, o.promptsAfter());
        Assert.assertEquals("one land tapped: " + o, 1, o.outcome().tapped().size());
        Assert.assertEquals(Set.of(o.suggested().get(0)), o.outcome().tapped());
    }

    /** Two sources in one answer: both tapped, no second prompt — where a source per prompt used to be the engine's loop. */
    @Test(timeout = 240_000)
    public void aPlanTapsEverySourceWithoutAFurtherPrompt() throws Exception {
        PlanOutcome o = playPlan("Grizzly Bears", List.of(), List.of("Island", "Forest"), "Forest", "Island", "Swamp");
        Assert.assertTrue("cast: " + o, o.outcome().cast());
        Assert.assertEquals("no further prompt: " + o, 0, o.promptsAfter());
        Assert.assertEquals(Set.of("Forest", "Island"), o.outcome().tapped());
        Assert.assertEquals(Set.of("Swamp"), o.outcome().untapped());
    }

    /**
     * Trygon Predator's {1}{G}{U} off Breeding Pool, Forest, Swamp and
     * Mountain (Swamp or Mountain for the {1}: asked): with both colours
     * owed, tapping the Pool by hand asks which to make. In a plan the Pool
     * is tapped for the blue nothing else makes, and nothing is asked.
     */
    @Test(timeout = 240_000)
    public void aDualInThePlanAsksNoColour() throws Exception {
        PlanOutcome o = playPlan("Trygon Predator", List.of(), List.of("Breeding Pool={U}", "Forest", "Swamp"), "Breeding Pool", "Forest", "Swamp", "Mountain");
        Assert.assertTrue("cast: " + o, o.outcome().cast());
        Assert.assertEquals("no colour question: " + o.outcome().log(), 0, o.otherPrompts());
        Assert.assertEquals("no further prompt: " + o, 0, o.promptsAfter());
        Assert.assertEquals(Set.of("Breeding Pool", "Forest", "Swamp"), o.outcome().tapped());
    }

    /**
     * Tap your own mana first: {G} and {B} floating, Grizzly Bears cast. The
     * suggestion is the float — Green for the {G}, Black for the {1} — never
     * the untapped Island, and the plan spends both pips in the one answer.
     */
    @Test(timeout = 240_000)
    public void floatingManaIsSuggestedFirstAndSpentInThePlan() throws Exception {
        PlanOutcome o = playPlan("Grizzly Bears", List.of("Forest", "Swamp"), null, "Forest", "Swamp", "Island");
        Assert.assertEquals("the float, one entry per pip: " + o, List.of("Green", "Black"), o.suggested());
        Assert.assertTrue("cast: " + o, o.outcome().cast());
        Assert.assertEquals("no further prompt: " + o, 0, o.promptsAfter());
        Assert.assertTrue("the Island stays up: " + o, o.outcome().untapped().contains("Island"));
    }

    /**
     * A plan that covers only part of the cost: the engine taps it, and the
     * prompt comes back for the rest — the Forest for {1}{G}'s {G}, then
     * Island or Swamp for the {1} is the person's again.
     */
    @Test(timeout = 240_000)
    public void aPlanThatUnderpaysComesBackForTheRest() throws Exception {
        PlanOutcome o = playPlan("Grizzly Bears", List.of(), List.of("Forest"), "Forest", "Island", "Swamp");
        Assert.assertEquals("asked again for the {1}: " + o.outcome().prompts(), 1, o.promptsAfter());
        Assert.assertTrue("what is left is asked: " + o.outcome().prompts(), o.outcome().prompts().get(1).startsWith("Pay {1}"));
    }

    /** A person's suggestion may tap a Signet, with the land that pays for it: Kaalia off Boros Signet, Plains and two Swamps. */
    @Test(timeout = 240_000)
    public void aSuggestionMayIncludeASignetAndPaysItsCostFromTheChosenLand() throws Exception {
        PlanOutcome o = playPlan("Kaalia of the Vast", List.of(), null, "Boros Signet", "Plains", "Swamp", "Swamp");
        Assert.assertTrue("the Signet is suggested: " + o, o.suggested().contains("Boros Signet"));
        Assert.assertEquals("four sources for {1}{R}{W}{B}, the Signet's land among them: " + o, 4, o.suggested().size());
        Assert.assertTrue("cast: " + o, o.outcome().cast());
        Assert.assertEquals("no further prompt: " + o.outcome().log(), 0, o.promptsAfter());
        Assert.assertEquals("no colour question: " + o.outcome().log(), 0, o.otherPrompts());
        Assert.assertTrue("everything tapped: " + o, o.outcome().untapped().isEmpty());
    }
}
