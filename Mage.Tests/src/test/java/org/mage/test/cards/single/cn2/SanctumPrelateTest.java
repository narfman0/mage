package org.mage.test.cards.single.cn2;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * Sanctum Prelate {1}{W}{W}: as it enters, choose a number. Noncreature spells with mana value equal to the chosen
 * number can't be cast.
 * <p>
 * The AI (playerA, under AI control while it resolves) names the noncreature mana value that hurts its opponents
 * most and itself least.
 */
public class SanctumPrelateTest extends CardTestPlayerBaseWithAIHelps {

    private static final String prelate = "Sanctum Prelate";

    private void castPrelate() {
        addCard(Zone.HAND, playerA, prelate, 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 3);
        removeAllCardsFromLibrary(playerA);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, prelate);
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
    }

    private int chosenNumber() {
        Permanent permanent = getPermanent(prelate, playerA);
        return (Integer) currentGame.getState().getValue(permanent.getId().toString());
    }

    @Test
    public void test_AI_NamesWhatItsOpponentCasts() {
        addCard(Zone.GRAVEYARD, playerB, "Lightning Bolt", 2);
        castPrelate();
        addCard(Zone.LIBRARY, playerA, "Counterspell", 5);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        Assert.assertEquals(1, chosenNumber());
    }

    @Test
    public void test_AI_AvoidsItsOwnSpells() {
        castPrelate();
        addCard(Zone.LIBRARY, playerA, "Counterspell", 5);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // nothing seen of B's: anything but its own Counterspells, nearest 2
        Assert.assertEquals(1, chosenNumber());
    }

    @Test
    public void test_AI_WithNothingSeen_NamesTwo() {
        castPrelate();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        Assert.assertEquals(2, chosenNumber());
    }
}
