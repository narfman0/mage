package mage.player.seat;

import mage.game.events.PlayerQueryEvent;

import java.util.List;
import java.util.Map;

/**
 * One question the engine asked a seat, rendered for the product, plus the
 * engine objects each numbered choice stands for (UUID, ManaType, a choice
 * key, or "special") so an answer by index maps back without a lookup.
 */
public final class Decision {

    public final int seq;
    public final PlayerQueryEvent event;
    public final Map<String, Object> result;
    public final List<Object> backing;

    Decision(int seq, PlayerQueryEvent event, Map<String, Object> result, List<Object> backing) {
        this.seq = seq;
        this.event = event;
        this.result = result;
        this.backing = backing;
    }

    public String actionType() {
        return (String) result.get("action_type");
    }

    public String combatPhase() {
        return (String) result.get("combat_phase");
    }
}
