package org.mage.test.cards.single.uds;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * Scrying Glass {2}: {3}, {T}: choose a number greater than 0 and a color. Target opponent reveals their hand. If
 * that opponent reveals exactly the chosen number of cards of the chosen color, you draw a card.
 * <p>
 * The AI (playerA, under AI control while it resolves) names 1 and the colour most seen among that opponent's
 * permanents.
 */
public class ScryingGlassTest extends CardTestPlayerBaseWithAIHelps {

    private void activateGlass() {
        addCard(Zone.BATTLEFIELD, playerA, "Scrying Glass", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1);
        // a card the AI can't play right away, so a draw stays in hand
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Craterhoof Behemoth", 3);
        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{3}, {T}: Choose", playerB);
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
    }

    @Test
    public void test_AI_NamesOneOfTheColourItSees() {
        addCard(Zone.HAND, playerB, "Lightning Bolt", 1);
        addCard(Zone.HAND, playerB, "Grizzly Bears", 1);
        activateGlass();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // one red card, and red is what B plays: A draws
        assertHandCount(playerA, "Craterhoof Behemoth", 1);
    }

    @Test
    public void test_AI_MissesAHandOfTwo() {
        addCard(Zone.HAND, playerB, "Lightning Bolt", 2);
        activateGlass();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 0);
    }
}
