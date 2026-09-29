package org.mage.test.cards.single.inv;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * Void {3}{B}{R}: choose a number. Destroy all artifacts and creatures with mana value equal to that number. Then
 * target player reveals their hand and discards all nonland cards with mana value equal to the number.
 * <p>
 * The AI (playerA, under AI control while it resolves) names the mana value with the best trade on the battlefield.
 */
public class VoidTest extends CardTestPlayerBaseWithAIHelps {

    private void castVoid() {
        addCard(Zone.HAND, playerA, "Void", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Badlands", 5);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Void", playerB);
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
    }

    @Test
    public void test_AI_NamesTheBestTrade() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1); // mana value 2
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1); // mana value 2
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 1); // mana value 4
        castVoid();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // 2 trades a Bears for a Bears; 4 takes the Giant alone
        assertGraveyardCount(playerB, "Hill Giant", 1);
        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertPermanentCount(playerB, "Grizzly Bears", 1);
    }

    @Test
    public void test_AI_WithNoTrade_SparesItsOwnAndNamesTwo() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1); // mana value 2
        addCard(Zone.HAND, playerB, "Hill Giant", 1); // mana value 4
        addCard(Zone.HAND, playerB, "Lightning Bolt", 1); // mana value 1
        castVoid();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // 2 would take its own Bears: 3, which misses B's hand
        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertHandCount(playerB, 2);
    }
}
