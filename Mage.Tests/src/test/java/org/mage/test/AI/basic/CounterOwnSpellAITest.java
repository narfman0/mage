package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestCommander4PlayersWithAIHelps;

/**
 * The CPU never counters its own spell. In a pod it cast Delay at an opponent's
 * Elder Gargaroth and then Flusterstorm at its own Delay, and the storm copies
 * retargeted onto its own Flusterstorms: the simulation removed only its own
 * permanents from a bad effect's targets (a spell is not a permanent), and a
 * copy's "may choose new targets" fell back to the AI's own spells when no
 * opponent's spell was legal.
 * <p>
 * Player order: A -> D -> C -> B; turn 3 is C's.
 */
public class CounterOwnSpellAITest extends CardTestCommander4PlayersWithAIHelps {

    @Test
    public void flusterstormIsNotCastAtItsOwnSpell() {
        addCard(Zone.HAND, playerC, "Elder Gargaroth"); // {3}{G}{G}
        addCard(Zone.BATTLEFIELD, playerC, "Forest", 5);
        addCard(Zone.HAND, playerA, "Delay"); // {1}{U} counter target spell
        addCard(Zone.HAND, playerA, "Flusterstorm"); // {U} counter target instant or sorcery unless {1}, storm
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        // a noncreature spell makes a token, like the report's Vraska trigger: a reason to cast anything
        addCard(Zone.BATTLEFIELD, playerA, "Young Pyromancer");

        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerC, "Elder Gargaroth");
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Delay", "Elder Gargaroth", "Elder Gargaroth");
        // the CPU's own decision with Delay on the stack and its only instant there
        aiPlayPriority(3, PhaseStep.PRECOMBAT_MAIN, playerA);

        setStopAt(3, PhaseStep.POSTCOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertHandCount(playerA, "Flusterstorm", 1);
        assertPermanentCount(playerC, "Elder Gargaroth", 0);
    }

    @Test
    public void aStormCopyKeepsItsTargetRatherThanTakeItsOwnSpell() {
        addCard(Zone.HAND, playerC, "Elder Gargaroth");
        addCard(Zone.BATTLEFIELD, playerC, "Forest", 5);
        addCard(Zone.HAND, playerA, "Delay");
        addCard(Zone.HAND, playerA, "Flusterstorm");
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);

        // the report's cast, made by hand: two spells before it, two copies
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerC, "Elder Gargaroth");
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Delay", "Elder Gargaroth", "Elder Gargaroth");
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Flusterstorm", "Delay", "Delay");
        // the storm trigger resolves; the AI chooses each copy's new target
        waitStackResolved(3, PhaseStep.PRECOMBAT_MAIN, playerA, true);
        runCode("no copy targets a Flusterstorm", 3, PhaseStep.PRECOMBAT_MAIN, playerA, (info, player, game) -> {
            int copies = 0;
            for (StackObject stackObject : game.getStack()) {
                if (!(stackObject instanceof Spell) || !stackObject.isCopy()
                        || !"Flusterstorm".equals(stackObject.getName())) {
                    continue;
                }
                copies++;
                StackObject target = game.getStack().getStackObject(stackObject.getStackAbility().getFirstTarget());
                Assert.assertNotNull(info + ": the copy has a target on the stack", target);
                Assert.assertEquals(info + ": the copy keeps Delay", "Delay", target.getName());
            }
            Assert.assertEquals(info + ": storm made two copies", 2, copies);
        });

        setStopAt(3, PhaseStep.POSTCOMBAT_MAIN);
        setStrictChooseMode(false); // the AI answers "change the target?" and picks the new one
        execute();

        assertGraveyardCount(playerA, "Delay", 1);
        assertGraveyardCount(playerA, "Flusterstorm", 1);
    }
}
