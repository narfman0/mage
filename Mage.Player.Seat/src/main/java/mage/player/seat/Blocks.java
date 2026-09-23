package mage.player.seat;

import mage.abilities.Ability;
import mage.abilities.keyword.FearAbility;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.HorsemanshipAbility;
import mage.abilities.keyword.IntimidateAbility;
import mage.abilities.keyword.LandwalkAbility;
import mage.abilities.keyword.ReachAbility;
import mage.abilities.keyword.ShadowAbility;
import mage.abilities.keyword.SkulkAbility;
import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.game.permanent.Permanent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Which blocks the rules allow, asked the way XMage itself asks when a
 * blocker is picked ({@code HumanPlayer.selectCombatGroup}): the attacker's
 * combat group must be attacking the blocker's controller, and
 * {@code Permanent.canBlock} must pass for every attacker in it (evasion,
 * restriction effects). XMage's own blockers window lists every creature
 * that could block <em>something</em> and checks a pair only once the
 * attacker is picked; the seat checks it up front, so the window can say
 * which pairs are legal and a batch never sends an illegal one
 * (fullpod docs/board-ui.md "Blocking").
 * <p>
 * Per pair only: "can't be blocked except by two or more" (menace) is a
 * check of the whole declaration, made by the engine at confirm; the
 * window carries it as {@code min_blockers} on the attacker.
 */
final class Blocks {

    private Blocks() {
    }

    static boolean canBlock(Game game, Permanent blocker, UUID attackerId) {
        CombatGroup group = game.getCombat().findGroup(attackerId);
        return blocker != null && group != null && group.canBlock(blocker, game);
    }

    /**
     * Why {@code blocker} can't block {@code attackerId}, as a line for the
     * person: "can't block Wind Drake (flying)" — the attacker's evasion
     * keyword when the seat can name one, the bare pair otherwise.
     */
    static String cantBlock(Game game, Permanent blocker, UUID attackerId) {
        Permanent attacker = game.getPermanent(attackerId);
        if (attacker == null) {
            return "can't block that attacker";
        }
        String why = evasion(game, blocker, attacker);
        return "can't block " + attacker.getName() + (why == null ? "" : " (" + why + ")");
    }

    /**
     * Why {@code blocker} can block none of the attackers attacking its
     * controller: one line naming each (up to three), or "can't block any
     * attacker" when there are more, or none attacks it.
     */
    static String cantBlockAny(Game game, Permanent blocker) {
        List<String> parts = new ArrayList<>();
        for (CombatGroup group : game.getCombat().getGroups()) {
            if (!blocker.getControllerId().equals(group.getDefendingPlayerId())) {
                continue;
            }
            for (UUID attackerId : group.getAttackers()) {
                Permanent attacker = game.getPermanent(attackerId);
                if (attacker != null) {
                    String why = evasion(game, blocker, attacker);
                    parts.add(attacker.getName() + (why == null ? "" : " (" + why + ")"));
                }
            }
        }
        if (parts.isEmpty() || parts.size() > 3) {
            return "can't block any attacker";
        }
        return "can't block " + String.join(" or ", parts);
    }

    /** The attacker's first evasion keyword the blocker doesn't answer, or null. */
    private static String evasion(Game game, Permanent blocker, Permanent attacker) {
        boolean blockerFlies = blocker.getAbilities(game).containsClass(FlyingAbility.class)
                || blocker.getAbilities(game).containsClass(ReachAbility.class);
        for (Ability ability : attacker.getAbilities(game)) {
            if (ability instanceof FlyingAbility && !blockerFlies) {
                return "flying";
            }
            if (ability instanceof ShadowAbility && !blocker.getAbilities(game).containsClass(ShadowAbility.class)) {
                return "shadow";
            }
            if (ability instanceof HorsemanshipAbility && !blocker.getAbilities(game).containsClass(HorsemanshipAbility.class)) {
                return "horsemanship";
            }
            if (ability instanceof FearAbility) {
                return "fear";
            }
            if (ability instanceof IntimidateAbility) {
                return "intimidate";
            }
            if (ability instanceof SkulkAbility) {
                return "skulk";
            }
            if (ability instanceof LandwalkAbility) {
                String rule = Fmt.stripHtml(ability.getRule());
                int paren = rule.indexOf(" (");
                return (paren > 0 ? rule.substring(0, paren) : rule).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }
}
