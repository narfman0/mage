package org.mage.test.cards.single.ons;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * Menacing Ogre {3}{R}{R}, 3/3: when it enters, each player secretly chooses a number. Each player with the highest
 * number loses that much life; if the controller is one of them, the Ogre gets two +1/+1 counters.
 * <p>
 * An AI opponent chooses 0; an AI controller chooses a small number to take the counters.
 */
public class MenacingOgreTest extends CardTestPlayerBaseWithAIHelps {

    private static final String ogre = "Menacing Ogre";

    private void castOgre() {
        addCard(Zone.HAND, playerA, ogre, 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 5);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, ogre);
    }

    @Test
    public void test_AI_Opponent_ChoosesZero() {
        castOgre();
        setChoice(playerA, "X=2");
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20);
        assertLife(playerA, 20 - 2);
        assertPowerToughness(playerA, ogre, 5, 5);
    }

    @Test
    public void test_AI_Controller_ChoosesSmallToTakeTheCounters() {
        castOgre();
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
        setChoice(playerB, "X=0");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        int life = currentGame.getPlayer(playerA.getId()).getLife();
        Assert.assertTrue("AI chooses 2 or 3, lost " + (20 - life), life == 17 || life == 18);
        assertLife(playerB, 20);
        assertPowerToughness(playerA, ogre, 5, 5);
    }

    @Test
    public void test_AI_Controller_AtLowLife_ChoosesOne() {
        setLife(playerA, 3);
        castOgre();
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
        setChoice(playerB, "X=0");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerA, 2);
        assertPowerToughness(playerA, ogre, 5, 5);
    }
}
