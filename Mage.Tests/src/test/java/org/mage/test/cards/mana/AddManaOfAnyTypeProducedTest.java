package org.mage.test.cards.mana;

import mage.constants.ManaType;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * @author LevelX2
 */
public class AddManaOfAnyTypeProducedTest extends CardTestPlayerBase {

    /**
     * Vorinclex, Voice of Hunger is not mana doubling River of Tears.
     */
    @Test
    public void testRiverOfTears() {
        // Trample
        // Whenever you tap a land for mana, add one mana of any type that land produced.
        // Whenever an opponent taps a land for mana, that land doesn't untap during its controller's next untap step.
        addCard(Zone.BATTLEFIELD, playerA, "Vorinclex, Voice of Hunger", 1);
        // {T}: Add {U}. If you played a land this turn, add {B} instead.
        addCard(Zone.BATTLEFIELD, playerA, "River of Tears", 1);
        addCard(Zone.HAND, playerA, "Vedalken Mastermind", 1);

        // because available mana calculation does not work correctly with Vorinclex, Voice of Hunger we have to tap the land manually
        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {U}");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Vedalken Mastermind");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Vedalken Mastermind", 1);
    }

    /**
     * Vorinclex, Voice of Hunger is not mana doubling River of Tears.
     */
    @Test
    public void testVorinclexVoiceofHungerRiverOfTearsManaMultiplier() {
        // Mana pools don't empty as steps and phases end.
        addCard(Zone.BATTLEFIELD, playerA, "Upwelling", 1);
        addCard(Zone.HAND, playerA, "River of Tears", 1);
        // Trample
        // Whenever you tap a land for mana, add one mana of any type that land produced.
        // Whenever an opponent taps a land for mana, that land doesn't untap during its controller's next untap step.
        addCard(Zone.BATTLEFIELD, playerA, "Vorinclex, Voice of Hunger", 1);
        // {T}: Add {U}. If you played a land this turn, add {B} instead.
        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "River of Tears");

        activateManaAbility(3, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {U}");

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.BEGIN_COMBAT);
        execute();

        assertManaPool(playerA, ManaType.BLUE, 2);
    }

    /**
     * Vorinclex glitches with Gemstone Cavern
     */
    @Test
    public void testGemstoneCavern() {
        // Trample
        // Whenever you tap a land for mana, add one mana of any type that land produced.
        // Whenever an opponent taps a land for mana, that land doesn't untap during its controller's next untap step.
        addCard(Zone.BATTLEFIELD, playerB, "Vorinclex, Voice of Hunger", 1); // {6}{G}{G}

        // If Gemstone Caverns is in your opening hand and you're not playing first, you may begin the game with Gemstone Caverns on the battlefield with a luck counter on it. If you do, exile a card from your hand.
        // {T}: Add {C}. If Gemstone Caverns has a luck counter on it, instead add one mana of any color.
        addCard(Zone.HAND, playerB, "Gemstone Caverns", 1);
        addCard(Zone.HAND, playerB, "Swamp", 1);

        addCard(Zone.HAND, playerB, "Silvercoat Lion", 2);

        // pay and put Gemstone to battlefield on starting
        setChoice(playerB, true);
        setChoice(playerB, "Swamp");

        activateManaAbility(2, PhaseStep.PRECOMBAT_MAIN, playerB, "{T}: Add");
        setChoice(playerB, "White");
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Silvercoat Lion");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerB, "Gemstone Caverns", 1);
        assertCounterCount("Gemstone Caverns", CounterType.LUCK, 1);
        assertPermanentCount(playerB, "Silvercoat Lion", 1);
        assertExileCount("Swamp", 1);
        assertTapped("Gemstone Caverns", true);

    }

    @Test
    public void testCastWithGemstoneCavern() {
        // Trample
        // Whenever you tap a land for mana, add one mana of any type that land produced.
        // Whenever an opponent taps a land for mana, that land doesn't untap during its controller's next untap step.
        addCard(Zone.HAND, playerB, "Vorinclex, Voice of Hunger", 1); // {6}{G}{G}
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 7);

        // If Gemstone Caverns is in your opening hand and you're not playing first, you may begin the game with Gemstone Caverns on the battlefield with a luck counter on it. If you do, exile a card from your hand.
        // {T}: Add {C}. If Gemstone Caverns has a luck counter on it, instead add one mana of any color.
        addCard(Zone.HAND, playerB, "Gemstone Caverns", 1);
        addCard(Zone.HAND, playerB, "Swamp", 1);

        addCard(Zone.HAND, playerB, "Silvercoat Lion", 2);

        // pay and put Gemstone to battlefield on starting
        setChoice(playerB, true);
        setChoice(playerB, "Swamp");

        activateManaAbility(2, PhaseStep.PRECOMBAT_MAIN, playerB, "{T}: Add {C}");
        setChoice(playerB, "White");
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Vorinclex, Voice of Hunger");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerB, "Gemstone Caverns", 1);
        assertCounterCount("Gemstone Caverns", CounterType.LUCK, 1);
        assertPermanentCount(playerB, "Vorinclex, Voice of Hunger", 1);
        assertTapped("Gemstone Caverns", true);
    }

    private static final String kinnan = "Kinnan, Bonder Prodigy";
    private static final String signet = "Gruul Signet";
    private static final String addRG = "{1}, {T}: Add {R}{G}";

    @Test
    public void testChooseColorRed() {
        addCard(Zone.BATTLEFIELD, playerA, kinnan); // Whenever you tap a nonland permanent for mana, add one mana of any type that permanent produced.
        addCard(Zone.BATTLEFIELD, playerA, "Wastes");
        addCard(Zone.BATTLEFIELD, playerA, signet);
        String raid = "Massive Raid"; // Massive Raid deals damage to any target equal to the number of creatures you control.
        addCard(Zone.HAND, playerA, raid); // 1RR

        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, addRG);
        setChoice(playerA, "Red");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, raid, playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertTapped(signet, true);
        assertTapped("Wastes", true);
        assertDamageReceived(playerA, kinnan, 0);
        assertLife(playerA, 20);
        assertLife(playerB, 19);

    }

    @Test
    public void testChooseColorGreen() {
        addCard(Zone.BATTLEFIELD, playerA, kinnan); // Whenever you tap a nonland permanent for mana, add one mana of any type that permanent produced.
        addCard(Zone.BATTLEFIELD, playerA, "Wastes");
        addCard(Zone.BATTLEFIELD, playerA, signet);
        String hailstorm = "Hail Storm"; // Hail Storm deals 2 damage to each attacking creature and 1 damage to you and each creature you control.
        addCard(Zone.HAND, playerA, hailstorm); // 1GG

        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, addRG);
        setChoice(playerA, "Green");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, hailstorm);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertTapped(signet, true);
        assertTapped("Wastes", true);
        assertDamageReceived(playerA, kinnan, 1);
        assertLife(playerA, 19);
        assertLife(playerB, 20);

    }


    /**
     * A source of any color is asked for its net mana before a color is
     * chosen, and the bonus mana must be counted for it too: with Kinnan and
     * an untapped Sylvan Caryatid, a spell that costs {2} is playable.
     */
    @Test
    public void testAnyColorSourceCountsTheBonusAsAvailable() {
        addCard(Zone.BATTLEFIELD, playerA, kinnan); // Whenever you tap a nonland permanent for mana, add one mana of any type that permanent produced.
        // {T}: Add one mana of any color.
        addCard(Zone.BATTLEFIELD, playerA, "Sylvan Caryatid");
        addCard(Zone.HAND, playerA, "Simic Signet"); // {2}

        checkPlayableAbility("signet with the bonus", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Simic Signet", true);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Simic Signet");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Simic Signet", 1);
        assertTapped("Sylvan Caryatid", true);
    }

    /**
     * The same for a land: Mana Flare and one City of Brass are two mana.
     */
    @Test
    public void testAnyColorLandCountsTheBonusAsAvailable() {
        // Whenever a player taps a land for mana, that player adds one mana of any type that land produced.
        addCard(Zone.BATTLEFIELD, playerA, "Mana Flare");
        // Whenever City of Brass becomes tapped, it deals 1 damage to you.
        // {T}: Add one mana of any color.
        addCard(Zone.BATTLEFIELD, playerA, "City of Brass");
        addCard(Zone.HAND, playerA, "Simic Signet"); // {2}

        checkPlayableAbility("signet with the bonus", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Simic Signet", true);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Simic Signet");

        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Simic Signet", 1);
        assertTapped("City of Brass", true);
        assertLife(playerA, 19);
    }

    /**
     * A source of {C} or any color (Gemstone Caverns with its luck counter
     * reads any color only) and a plain colored source keep their bonus: one
     * Elvish Mystic under Kinnan is {G}{G}, not more and not any color.
     */
    @Test
    public void testColoredSourceStillCountsItsOwnColorOnly() {
        addCard(Zone.BATTLEFIELD, playerA, kinnan);
        addCard(Zone.BATTLEFIELD, playerA, "Elvish Mystic"); // {T}: Add {G}.
        addCard(Zone.HAND, playerA, "Grizzly Bears"); // {1}{G}
        addCard(Zone.HAND, playerA, "Simic Signet"); // {2}
        addCard(Zone.HAND, playerA, "Coral Merfolk"); // {1}{U}

        checkPlayableAbility("bears", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Grizzly Bears", true);
        checkPlayableAbility("signet", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Simic Signet", true);
        checkPlayableAbility("no blue", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Coral Merfolk", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();
    }


    /**
     * A source whose one ability offers several kinds (Fellwar Stone over an
     * opponent's Forest and Island: {G} or {U}) is counted with its bonus
     * too, once: {G}{G} or {U}{U}, so a {2} spell is playable and a {3} spell
     * is not.
     */
    @Test
    public void testSeveralKindsSourceCountsTheBonusOnce() {
        addCard(Zone.BATTLEFIELD, playerA, kinnan);
        // {T}: Add one mana of any color that a land an opponent controls could produce.
        addCard(Zone.BATTLEFIELD, playerA, "Fellwar Stone");
        addCard(Zone.BATTLEFIELD, playerB, "Forest");
        addCard(Zone.BATTLEFIELD, playerB, "Island");
        addCard(Zone.HAND, playerA, "Simic Signet"); // {2}
        addCard(Zone.HAND, playerA, "Coral Merfolk"); // {1}{U}
        addCard(Zone.HAND, playerA, "Trained Armodon"); // {1}{G}{G}

        checkPlayableAbility("signet", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Simic Signet", true);
        checkPlayableAbility("merfolk", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Coral Merfolk", true);
        checkPlayableAbility("two mana, not three", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Trained Armodon", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();
    }

    /**
     * The bonus of one source is never credited to the next: Fellwar Stone
     * and an Elvish Mystic under Kinnan are four mana, not more.
     */
    @Test
    public void testSeveralKindsSourceLeavesNothingForTheNextSource() {
        addCard(Zone.BATTLEFIELD, playerA, kinnan);
        addCard(Zone.BATTLEFIELD, playerA, "Fellwar Stone");
        addCard(Zone.BATTLEFIELD, playerA, "Elvish Mystic"); // {T}: Add {G}.
        addCard(Zone.BATTLEFIELD, playerB, "Forest");
        addCard(Zone.BATTLEFIELD, playerB, "Island");
        addCard(Zone.HAND, playerA, "Craw Wurm"); // {4}{G}{G}
        addCard(Zone.HAND, playerA, "Durkwood Boars"); // {4}{G}
        addCard(Zone.HAND, playerA, "Giant Spider"); // {3}{G}

        checkPlayableAbility("four", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Giant Spider", true);
        checkPlayableAbility("not five", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Durkwood Boars", false);
        checkPlayableAbility("not six", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Craw Wurm", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();
    }

}
