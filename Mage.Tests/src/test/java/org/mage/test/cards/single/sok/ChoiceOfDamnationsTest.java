package org.mage.test.cards.single.sok;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * Choice of Damnations {5}{B}: target opponent chooses a number. You may have that player lose that much life. If
 * you don't, that player sacrifices all but that many permanents.
 * <p>
 * The number is the opponent's, the life-or-sacrifice choice is the caster's: an AI opponent doesn't take the
 * caster's choice away.
 */
public class ChoiceOfDamnationsTest extends CardTestPlayerBaseWithAIHelps {

    @Test
    public void test_AI_Target_LeavesTheChoiceToTheCaster() {
        setLife(playerB, 6);
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 5);
        addCard(Zone.HAND, playerA, "Choice of Damnations", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 6);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Choice of Damnations", playerB);
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerB);
        // B (AI) chooses half its life, 3; A chooses sacrifice over life loss
        setChoice(playerA, false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 6);
        assertPermanentCount(playerB, "Forest", 3);
    }
}
