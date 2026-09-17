package mage.player.seat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A scripted player for the harness: keeps, plays a land, casts what it can,
 * attacks with everything, blocks with the first legal pairing, declines the
 * rest. Counts what it saw so tests can assert on the decisions themselves.
 */
final class ScriptedSeat {

    final List<Map<String, Object>> seen = new ArrayList<>();
    int manaPrompts;
    int attacks;
    int blocks;
    int casts;
    int lands;

    /** The choose_action arguments for a decision. */
    Map<String, Object> answer(Map<String, Object> d) {
        seen.add(d);
        Map<String, Object> args = new HashMap<>();
        String type = String.valueOf(d.get("action_type"));
        String responseType = String.valueOf(d.get("response_type"));
        Object combat = d.get("combat_phase");
        List<Map<String, Object>> choices = choices(d);
        switch (type) {
            case "GAME_SELECT" -> {
                if ("declare_attackers".equals(combat)) {
                    boolean any = choices.stream().anyMatch(c -> "attacker".equals(c.get("choice_type")));
                    if (any) {
                        attacks++;
                        args.put("attackers", "all");
                    } else {
                        args.put("choice", "no");
                    }
                    return args;
                }
                if ("declare_blockers".equals(combat)) {
                    Object incoming = d.get("incoming_attackers");
                    for (Map<String, Object> c : choices) {
                        if ("blocker".equals(c.get("choice_type")) && incoming instanceof List<?> l && !l.isEmpty()) {
                            blocks++;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> first = (Map<String, Object>) l.get(0);
                            args.put("blockers", c.get("id") + ":" + first.get("id"));
                            return args;
                        }
                    }
                    args.put("choice", "no");
                    return args;
                }
                if ("select".equals(responseType)) {
                    for (Map<String, Object> c : choices) {
                        if ("land".equals(c.get("action"))) {
                            lands++;
                            args.put("choice", String.valueOf(c.get("index")));
                            return args;
                        }
                    }
                    for (Map<String, Object> c : choices) {
                        if ("cast".equals(c.get("action"))) {
                            casts++;
                            args.put("choice", String.valueOf(c.get("index")));
                            return args;
                        }
                    }
                }
                args.put("choice", "no");
            }
            case "GAME_PLAY_MANA", "GAME_PLAY_XMANA" -> {
                manaPrompts++;
                args.put("choice", "no");
            }
            case "GAME_TARGET" -> args.put("choice", Boolean.TRUE.equals(d.get("required")) || !choices.isEmpty() ? "0" : "no");
            case "GAME_CHOOSE_ABILITY", "GAME_CHOOSE_CHOICE" -> args.put("choice", "0");
            case "GAME_GET_AMOUNT" -> args.put("amount", d.get("min"));
            case "GAME_CHOOSE_PILE" -> args.put("pile", 1);
            default -> args.put("choice", "no");
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> choices(Map<String, Object> d) {
        Object c = d.get("choices");
        return c instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
