package org.mage.test.cards.single.pls;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * Goblin Game {5}{R}{R}: each player hides at least one item (chooses a number greater than 0). Each player loses
 * that much life, and whoever chose the fewest then loses half their life, rounded up.
 * <p>
 * The AI (playerB, under AI control while it resolves) hides 2 or 3, and less when tying for fewest would kill it.
 */
public class GoblinGameTest extends CardTestPlayerBaseWithAIHelps {

    private void castGoblinGame(int number) {
        addCard(Zone.HAND, playerA, "Goblin Game", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 7);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Goblin Game");
        setChoice(playerA, "X=" + number);
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerB);
    }

    @Test
    public void test_AI_HidesTwoOrThree() {
        castGoblinGame(1);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // A hid fewest: 1, then half of 19 rounded up
        assertLife(playerA, 20 - 1 - 10);
        int life = currentGame.getPlayer(playerB.getId()).getLife();
        Assert.assertTrue("AI hides 2 or 3, lost " + (20 - life), life == 17 || life == 18);
    }

    @Test
    public void test_AI_AtLowLife_HidesOne() {
        setLife(playerB, 3);
        castGoblinGame(2);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // 2 would kill B if A also hid 2 (2, then half of 1 rounded up): B hid 1, lost 1, then half of 2
        assertLife(playerB, 1);
        assertLife(playerA, 20 - 2);
        Assert.assertFalse(currentGame.getPlayer(playerB.getId()).hasLost());
    }
}
