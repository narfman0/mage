package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * AI choices that read as broken to a player when made at random: separating and choosing piles, and choosing
 * a number from a wide range.
 */
public class PilesAndNumbersAITest extends CardTestPlayerBase {

    private void addPileLibrary() {
        removeAllCardsFromLibrary(playerA);
        // pile values (2 + mana value): 8, 4, 4, 3, 2 -- the even split is {Craw Wurm, Lightning Bolt} = 11
        // against {Grizzly Bears, Grizzly Bears, Island} = 10
        addCard(Zone.LIBRARY, playerA, "Craw Wurm", 1);
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears", 2);
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt", 1);
        addCard(Zone.LIBRARY, playerA, "Island", 1);
    }

    @Test
    public void test_OpponentSeparates_NeverZeroAndFive_ChooserTakesTheBetterPile() {
        // Reveal the top five cards of your library. An opponent separates those cards into two piles.
        // Put one pile into your hand and the rest into your graveyard.
        addCard(Zone.HAND, playerA, "Fact or Fiction", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 4);
        addPileLibrary();

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Fact or Fiction");

        // the AI separates for playerB and chooses for playerA
        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 2);
        assertHandCount(playerA, "Craw Wurm", 1);
        assertHandCount(playerA, "Lightning Bolt", 1);
        assertGraveyardCount(playerA, 4); // Fact or Fiction and the other pile
    }

    @Test
    public void test_OpponentChooses_GivesTheWorsePile() {
        // Reveal the top five cards of your library and separate them into two piles. An opponent chooses one
        // of those piles. Put that pile into your hand and the other into your graveyard.
        addCard(Zone.HAND, playerA, "Steam Augury", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Volcanic Island", 4);
        addPileLibrary();

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Steam Augury");

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 3);
        assertHandCount(playerA, "Grizzly Bears", 2);
        assertHandCount(playerA, "Island", 1);
    }

    @Test
    public void test_WheelOfMisfortune_AIPicksALowNumber() {
        // Each player secretly chooses a number 0 or greater ... deals damage equal to the highest number to
        // each player who chose that number.
        addCard(Zone.HAND, playerA, "Wheel of Misfortune", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 4);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Wheel of Misfortune");
        setChoice(playerA, "X=5");

        // playerB is the AI: a number from 0..1000, but low
        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerA, 20 - 5);
        assertLife(playerB, 20);
    }

    @Test
    public void test_WheelOfMisfortune_AINeverPicksItsLife() {
        addCard(Zone.HAND, playerA, "Wheel of Misfortune", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 4);
        setLife(playerB, 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Wheel of Misfortune");
        setChoice(playerA, "X=0");

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerA, 20);
        Assert.assertTrue("AI must survive its own number", currentGame.getPlayer(playerB.getId()).getLife() >= 1);
        Assert.assertFalse(currentGame.getPlayer(playerB.getId()).hasLost());
    }
}
