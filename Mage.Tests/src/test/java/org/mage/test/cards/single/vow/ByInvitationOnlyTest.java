package org.mage.test.cards.single.vow;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * By Invitation Only {3}{W}{W}: choose a number between 0 and 13. Each player sacrifices that many creatures.
 * <p>
 * The AI (playerA, under AI control while it resolves) picks the number that costs its opponents the most of what
 * they would keep, less its own loss, each creature weighted by mana value; nothing ahead, 0.
 */
public class ByInvitationOnlyTest extends CardTestPlayerBaseWithAIHelps {

    private void castByInvitationOnly() {
        addCard(Zone.HAND, playerA, "By Invitation Only", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 5);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "By Invitation Only");
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
    }

    @Test
    public void test_AI_ClearsTheOpposingBoardForItsOwnElf() {
        addCard(Zone.BATTLEFIELD, playerA, "Llanowar Elves", 1); // MV 1
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1); // MV 2
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 1); // MV 4
        addCard(Zone.BATTLEFIELD, playerB, "Serra Angel", 1); // MV 5
        addCard(Zone.BATTLEFIELD, playerB, "Air Elemental", 1); // MV 5
        addCard(Zone.BATTLEFIELD, playerB, "Craw Wurm", 1); // MV 6
        castByInvitationOnly();

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // 5: every opposing creature for the Elves (the generic pick is 3 at most)
        assertGraveyardCount(playerA, "Llanowar Elves", 1);
        assertPermanentCount(playerB, 0);
        assertGraveyardCount(playerB, 5);
    }

    @Test
    public void test_AI_StopsWhereItsOwnThreatWouldGo() {
        addCard(Zone.BATTLEFIELD, playerA, "Llanowar Elves", 1); // MV 1
        addCard(Zone.BATTLEFIELD, playerA, "Craterhoof Behemoth", 1); // MV 8
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 2); // MV 2
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant", 1); // MV 4
        castByInvitationOnly();

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // 1: an Elf for a Bear; 2 or 3 would cost the Behemoth
        assertPermanentCount(playerA, "Craterhoof Behemoth", 1);
        assertGraveyardCount(playerA, "Llanowar Elves", 1);
        assertPermanentCount(playerB, 2);
        assertGraveyardCount(playerB, 1);
    }

    @Test
    public void test_AI_WithNoGoodTrade_ChoosesZero() {
        addCard(Zone.BATTLEFIELD, playerA, "Craterhoof Behemoth", 1); // MV 8
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1); // MV 2
        castByInvitationOnly();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // any number that takes the Bears takes the Behemoth too: 0, and nothing dies
        assertPermanentCount(playerA, "Craterhoof Behemoth", 1);
        assertPermanentCount(playerB, "Grizzly Bears", 1);
    }
}
