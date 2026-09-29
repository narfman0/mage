package org.mage.test.cards.single.apc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * Squee's Revenge {1}{U}{R}: choose a number. Flip a coin that many times or until you lose a flip. If you win all
 * the flips, draw two cards for each flip.
 * <p>
 * The AI (playerA, under AI control while it resolves) chooses 1 or 2, where the expected cards peak.
 */
public class SqueesRevengeTest extends CardTestPlayerBaseWithAIHelps {

    private void castSquees() {
        addCard(Zone.HAND, playerA, "Squee's Revenge", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Volcanic Island", 3);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Squee's Revenge");
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
    }

    @Test
    public void test_AI_FlipsAtLeastOnce() {
        castSquees();
        // a lost first flip ends it; 0 flips would leave this result unused
        setFlipCoinResult(playerA, false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 0);
    }

    @Test
    public void test_AI_FlipsOneOrTwo() {
        // cards the AI can't play right away, so every draw stays in hand
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Craterhoof Behemoth", 10);
        castSquees();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // 1 or 2 flips: nothing, two cards or four; three flips could draw six
        int hand = currentGame.getPlayer(playerA.getId()).getHand().size();
        Assert.assertTrue("AI flips 1 or 2, drew " + hand, hand == 0 || hand == 2 || hand == 4);
    }

    @Test
    public void test_AI_WithAThinLibrary_DoesNotFlip() {
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Island", 1);
        castSquees();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // two cards from a one-card library would lose the game: 0 flips
        assertHandCount(playerA, 0);
        assertLibraryCount(playerA, 1);
        Assert.assertFalse(currentGame.getPlayer(playerA.getId()).hasLost());
    }
}
