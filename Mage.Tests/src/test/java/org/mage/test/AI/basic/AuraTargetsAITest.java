package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * A helpful Aura goes on the caster's own permanent or stays in hand. With no
 * Forest of its own the CPU cast Utopia Sprawl on the player's Forest, ramping
 * them: the simulation's target optimizer had rightly removed the opponent's
 * Forests, and the ability was then added with no target at all, which
 * chooseTarget's required-target fallback filled with the opponent's.
 */
public class AuraTargetsAITest extends CardTestPlayerBaseWithAIHelps {

    @Test
    public void aHelpfulAuraIsNotCastWhenOnlyAnOpponentCanWearIt() {
        addCard(Zone.HAND, playerA, "Utopia Sprawl"); // {G}, enchant Forest
        addCard(Zone.BATTLEFIELD, playerA, "Mox Emerald"); // {G} without a Forest
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 2);

        aiPlayPriority(1, PhaseStep.PRECOMBAT_MAIN, playerA);

        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertHandCount(playerA, "Utopia Sprawl", 1);
        assertPermanentCount(playerA, "Utopia Sprawl", 0);
    }

    @Test
    public void aHelpfulAuraGoesOnItsOwnPermanent() {
        // Rancor: +2/+0 and trample, the kind of Aura the evaluator does value
        // (Utopia Sprawl's mana is not scored, so it is never cast at all).
        addCard(Zone.HAND, playerA, "Rancor"); // {G}, enchant creature
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        aiPlayPriority(1, PhaseStep.PRECOMBAT_MAIN, playerA);

        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertPermanentCount(playerA, "Rancor", 1);
        Permanent rancor = getPermanent("Rancor", playerA);
        Permanent bears = currentGame.getPermanent(rancor.getAttachedTo());
        Assert.assertNotNull("attached", bears);
        Assert.assertEquals("on its own creature", playerA.getId(), bears.getControllerId());
    }

    @Test
    public void aHelpfulAuraStaysInHandWithNoCreatureOfItsOwn() {
        addCard(Zone.HAND, playerA, "Rancor");
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        aiPlayPriority(1, PhaseStep.PRECOMBAT_MAIN, playerA);

        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertHandCount(playerA, "Rancor", 1);
        assertPermanentCount(playerA, "Rancor", 0);
    }
}
