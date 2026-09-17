package mage.collectors;

import mage.collectors.services.ReplayScript;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayScriptTest {

    @Test
    void parsesDecisionsPerPlayerInOrderAndLogsWithoutRefs() {
        ReplayScript s = ReplayScript.parse(List.of(
            "{\"seq\":0,\"type\":\"game_start\",\"players\":[{\"name\":\"A\"},{\"name\":\"B\"}]}",
            "{\"seq\":1,\"type\":\"decision\",\"player\":\"A\",\"query_type\":\"PICK_TARGET\",\"response\":{\"type\":\"uuid\",\"id\":\"p2\",\"name\":\"Unknown\"}}",
            "{\"seq\":5,\"type\":\"decision\",\"player\":\"A\",\"query_type\":\"ASK\",\"response\":{\"type\":\"boolean\",\"value\":false}}",
            "{\"seq\":6,\"type\":\"decision\",\"player\":\"B\",\"query_type\":\"ASK\",\"response\":{\"type\":\"boolean\",\"value\":true}}",
            "{\"seq\":16,\"type\":\"game_action\",\"message\":\"A puts Mountain [ae3] from hand onto the Battlefield\"}",
            "not json",
            "{\"seq\":18,\"type\":\"decision\",\"player\":\"A\",\"query_type\":\"SELECT\",\"response\":{\"type\":\"pass\"}}"
        ));
        assertEquals(List.of("A", "B"), s.playerNames());
        assertEquals(4, s.total());
        assertEquals(4, s.remaining());
        assertEquals("PICK_TARGET", s.next("A").queryType());
        assertEquals("p2", s.peek("A") == null ? null : "p2");
        assertEquals("ASK", s.next("A").queryType());
        assertEquals("pass", s.next("A").responseType());
        assertNull(s.next("A"));
        assertEquals(1, s.remaining());
        assertTrue(s.next("B").value().getAsBoolean());
        assertTrue(s.exhausted());
        assertEquals("A puts Mountain from hand onto the Battlefield", s.nextLog());
        assertNull(s.nextLog());
    }

    @Test
    void abilityChoicesCarryTheirIndexAndRule() {
        ReplayScript s = ReplayScript.parse(List.of(
            "{\"seq\":3,\"type\":\"decision\",\"player\":\"A\",\"query_type\":\"CHOOSE_ABILITY\","
                + "\"response\":{\"type\":\"uuid\",\"id\":\"p29\",\"ability_index\":1,\"name\":\"{2}, {T}: copy target land.\"}}",
            "{\"seq\":4,\"type\":\"decision\",\"player\":\"A\",\"query_type\":\"SELECT\",\"response\":{\"type\":\"uuid\",\"id\":\"p7\",\"name\":\"Island\"}}"
        ));
        ReplayScript.Decision ability = s.next("A");
        assertEquals(1, ability.abilityIndex());
        assertEquals("{2}, {T}: copy target land.", ability.name());
        assertNull(s.next("A").abilityIndex());
    }

    @Test
    void anOpenQuestionIsNotADecisionToFeed() {
        ReplayScript s = ReplayScript.parse(List.of(
            "{\"seq\":5,\"type\":\"decision\",\"player\":\"A\",\"query_type\":\"ASK\",\"response\":{\"type\":\"boolean\",\"value\":false}}",
            "{\"seq\":9,\"type\":\"decision\",\"player\":\"A\",\"query_type\":\"SELECT\",\"message\":\"Play spells and abilities\",\"response\":{\"type\":\"open\"}}",
            "{\"seq\":10,\"type\":\"game_end\",\"winner\":null}"
        ));
        assertEquals(1, s.total());
        assertEquals("ASK", s.next("A").queryType());
        assertTrue(s.exhausted());
    }

    @Test
    void modeChoicesCarryTheirIndexAndText() {
        ReplayScript s = ReplayScript.parse(List.of(
            "{\"seq\":125,\"type\":\"decision\",\"player\":\"A\",\"query_type\":\"CHOOSE_MODE\","
                + "\"response\":{\"type\":\"uuid\",\"id\":\"p66\",\"mode_index\":0,\"name\":\"1. Boros Charm deals 4 damage to target player or planeswalker.\"}}",
            "{\"seq\":126,\"type\":\"decision\",\"player\":\"A\",\"query_type\":\"SELECT\",\"response\":{\"type\":\"uuid\",\"id\":\"p7\",\"name\":\"Island\"}}"
        ));
        ReplayScript.Decision mode = s.next("A");
        assertEquals(0, mode.modeIndex());
        assertNull(mode.abilityIndex());
        assertEquals("1. Boros Charm deals 4 damage to target player or planeswalker.", mode.name());
        assertNull(s.next("A").modeIndex());
    }

    @Test
    void normalizeStripsEveryEngineRef() {
        assertEquals("Bolt targets Memnite", ReplayScript.normalizeLog("Bolt [1ab] targets Memnite [c70]"));
        assertEquals("AI-003-04e0's library is shuffled",
            ReplayScript.normalizeLog("<font color='#20B2AA'>AI-003-04e0</font>'s library is shuffled"));
        assertEquals("", ReplayScript.normalizeLog(null));
        // The recorder's line went through Jsoup, which collapses whitespace; a live one may not have.
        assertEquals("Pile 1, going to HAND: (none)", ReplayScript.normalizeLog("Pile 1, going to HAND:  (none)"));
    }
}
