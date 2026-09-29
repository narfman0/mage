package org.mage.test.cards.single.mh2;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * Yusri, Fortune's Flame {1}{U}{R}, 2/3 flier: whenever it attacks, choose a number between 1 and 5. Flip that many
 * coins; draw a card for each win, take 2 damage for each loss. Five wins: free spells from hand this turn.
 * <p>
 * The AI (playerA, under AI control through combat) flips 2 by default, 1 at low life, 5 only when far ahead.
 * The flips are set, so a wrong count leaves a result unused or flips one at random.
 */
public class YusriFortunesFlameTest extends CardTestPlayerBaseWithAIHelps {

    private static final String yusri = "Yusri, Fortune's Flame";

    private void attackWithYusri() {
        addCard(Zone.BATTLEFIELD, playerA, yusri, 1);
        attack(1, playerA, yusri);
        aiPlayStep(1, PhaseStep.DECLARE_ATTACKERS, playerA);
    }

    @Test
    public void test_AI_FlipsTwo() {
        attackWithYusri();
        setFlipCoinResult(playerA, true);
        setFlipCoinResult(playerA, false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 1);
        assertLife(playerA, 20 - 2);
        assertLife(playerB, 20 - 2);
    }

    @Test
    public void test_AI_AtLowLife_FlipsOne() {
        setLife(playerA, 8);
        attackWithYusri();
        setFlipCoinResult(playerA, false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 0);
        assertLife(playerA, 8 - 2);
    }

    @Test
    public void test_AI_FarAheadWithBigSpells_FlipsFive() {
        setLife(playerA, 30);
        setLife(playerB, 10);
        addCard(Zone.HAND, playerA, "Craterhoof Behemoth", 2);
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Mountain", 10);
        attackWithYusri();
        for (int i = 0; i < 5; i++) {
            setFlipCoinResult(playerA, true);
        }

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 2 + 5);
        assertLife(playerA, 30);
    }
}
