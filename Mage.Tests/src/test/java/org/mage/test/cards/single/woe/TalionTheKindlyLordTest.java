package org.mage.test.cards.single.woe;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

import java.util.Map;

/**
 * Talion, the Kindly Lord {2}{U}{B}: as it enters, choose a number between 1 and 10. Whenever an opponent casts a
 * spell with mana value, power, or toughness equal to the chosen number, that player loses 2 life and you draw.
 * <p>
 * The AI (playerA, under AI control while Talion resolves) names the mana value its opponents play most.
 */
public class TalionTheKindlyLordTest extends CardTestPlayerBaseWithAIHelps {

    private static final String talion = "Talion, the Kindly Lord";

    private void castTalion() {
        addCard(Zone.HAND, playerA, talion, 1);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 2);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, talion);
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
    }

    private int chosenNumber() {
        Permanent permanent = getPermanent(talion, playerA);
        Map<String, Object> values = currentGame.getState().getValues("chosenNumber_" + permanent.getId());
        Assert.assertEquals("one chosen number", 1, values.size());
        return (Integer) values.values().iterator().next();
    }

    @Test
    public void test_AI_NamesTheManaValueItsOpponentPlaysMost() {
        addCard(Zone.GRAVEYARD, playerB, "Concentrate", 3); // mana value 4
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1); // mana value 2
        castTalion();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        Assert.assertEquals(4, chosenNumber());
    }

    @Test
    public void test_AI_WithNothingSeen_NamesACommonManaValue() {
        castTalion();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        int number = chosenNumber();
        Assert.assertTrue("AI names 2 or 3, named " + number, number == 2 || number == 3);
    }
}
