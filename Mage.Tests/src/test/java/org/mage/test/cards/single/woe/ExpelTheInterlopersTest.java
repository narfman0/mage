package org.mage.test.cards.single.woe;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * Expel the Interlopers {3}{W}{W}: choose a number between 0 and 10. Destroy all creatures with power greater than
 * or equal to the chosen number.
 * <p>
 * The AI (playerA, under AI control while it resolves) picks the threshold with the best trade.
 */
public class ExpelTheInterlopersTest extends CardTestPlayerBaseWithAIHelps {

    private void castExpel() {
        addCard(Zone.HAND, playerA, "Expel the Interlopers", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 5);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Expel the Interlopers");
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
    }

    @Test
    public void test_AI_SweepsAboveItsOwnCreatures() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1); // 2/2
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 1); // 3/3
        addCard(Zone.BATTLEFIELD, playerB, "Serra Angel", 1); // 4/4
        castExpel();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // 3: both of B's creatures, none of A's
        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertGraveyardCount(playerB, "Hill Giant", 1);
        assertGraveyardCount(playerB, "Serra Angel", 1);
    }

    @Test
    public void test_AI_WithNoGoodTrade_ChoosesTen() {
        addCard(Zone.BATTLEFIELD, playerA, "Craterhoof Behemoth", 1); // 5/5
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1); // 2/2
        castExpel();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // any number that takes the Bears takes the Behemoth too: 10, and nothing dies
        assertPermanentCount(playerA, "Craterhoof Behemoth", 1);
        assertPermanentCount(playerB, "Grizzly Bears", 1);
    }
}
