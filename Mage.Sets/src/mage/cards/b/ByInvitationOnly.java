package mage.cards.b;

import mage.abilities.Ability;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.SacrificeAllEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author TheElk801
 */
public final class ByInvitationOnly extends CardImpl {

    public ByInvitationOnly(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.SORCERY}, "{3}{W}{W}");

        // Choose a number between 0 and 13. Each player sacrifices that many creatures.
        this.getSpellAbility().addEffect(new ByInvitationOnlyEffect());
    }

    private ByInvitationOnly(final ByInvitationOnly card) {
        super(card);
    }

    @Override
    public ByInvitationOnly copy() {
        return new ByInvitationOnly(this);
    }
}

class ByInvitationOnlyEffect extends OneShotEffect {

    ByInvitationOnlyEffect() {
        super(Outcome.Benefit);
        staticText = "choose a number between 0 and 13. Each player sacrifices that many creatures";
    }

    private ByInvitationOnlyEffect(final ByInvitationOnlyEffect effect) {
        super(effect);
    }

    @Override
    public ByInvitationOnlyEffect copy() {
        return new ByInvitationOnlyEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(source.getControllerId());
        if (player == null) {
            return false;
        }
        // AI hint
        int number = player.isComputer()
                ? chooseNumberAI(player, game)
                : player.getAmount(0, 13, "Choose a number between 0 and 13", source, game);
        return new SacrificeAllEffect(
                number, StaticFilters.FILTER_PERMANENT_CREATURE
        ).apply(game, source);
    }

    /**
     * The number that costs its opponents the most of what they would keep, less its own loss. Each player
     * sacrifices creatures of their own choosing, so a number n costs each player their n least valuable creatures
     * (all of them if they have fewer); each creature is weighted by mana value (at least 1), as Expel the
     * Interlopers weighs them. Each number from 0 to 13 scores its opponents' loss as a gain and its own as a loss;
     * ties go to the smaller number. If no number comes out ahead, 0.
     */
    static int chooseNumberAI(Player player, Game game) {
        Map<UUID, List<Integer>> values = new HashMap<>();
        for (Permanent creature : game.getBattlefield().getActivePermanents(
                StaticFilters.FILTER_PERMANENT_CREATURE, player.getId(), game)) {
            UUID controllerId = creature.getControllerId();
            if (!controllerId.equals(player.getId()) && !game.isOpponent(player, controllerId)) {
                continue;
            }
            values.computeIfAbsent(controllerId, k -> new ArrayList<>()).add(Math.max(1, creature.getManaValue()));
        }
        values.values().forEach(Collections::sort);
        int bestNumber = 0;
        int bestScore = 0;
        for (int number = 1; number <= 13; number++) {
            int score = 0;
            for (Map.Entry<UUID, List<Integer>> entry : values.entrySet()) {
                List<Integer> creatures = entry.getValue();
                int lost = 0;
                for (int i = 0; i < Math.min(number, creatures.size()); i++) {
                    lost += creatures.get(i);
                }
                score += entry.getKey().equals(player.getId()) ? -lost : lost;
            }
            if (score > bestScore) {
                bestScore = score;
                bestNumber = number;
            }
        }
        return bestNumber;
    }
}
