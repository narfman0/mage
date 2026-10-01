package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * An activated ability that leaves the board as it found it is not an option.
 * Seeker of Skybreak ({T}: Untap target creature) targeting itself scores the
 * same as passing, and the passivity penalty made it the choice: the CPU
 * untapped it 2,340 times over 18 minutes on an opponent's turn, holding the
 * table. The search now drops an action whose resolution changed nothing.
 */
public class NoOpActionAITest extends CardTestPlayerBaseWithAIHelps {

    // No JUnit timeout: the framework's game code must run on the test thread. A CPU that
    // still loops hangs the run (surefire's -Dsurefire.timeout bounds it).
    @Test
    public void anAbilityThatLeavesTheBoardAsItFoundItIsNeverChosen() {
        addCard(Zone.BATTLEFIELD, playerA, "Seeker of Skybreak");
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        // The opponent's main phase, nothing to do but the Seeker's own untap:
        // the step ends (a CPU that keeps untapping never gets here) and the
        // Seeker was never activated.
        aiPlayStep(2, PhaseStep.PRECOMBAT_MAIN, playerA);

        setStopAt(2, PhaseStep.POSTCOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertTapped("Seeker of Skybreak", false);
    }
}
