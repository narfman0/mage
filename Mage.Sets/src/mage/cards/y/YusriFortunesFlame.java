package mage.cards.y;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.AttacksTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.continuous.CastFromHandWithoutPayingManaCostEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.*;
import mage.game.Game;
import mage.players.Player;

import java.util.UUID;

/**
 * @author TheElk801
 */
public final class YusriFortunesFlame extends CardImpl {

    public YusriFortunesFlame(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{U}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.EFREET);
        this.power = new MageInt(2);
        this.toughness = new MageInt(3);

        // Flying
        this.addAbility(FlyingAbility.getInstance());

        // Whenever Yusri, Fortune's Flame attacks, choose a number between 1 and 5. Flip that many coins. For each flip you win, draw a card. For each flip you lose, Yursi deals 2 damage to you. You you won five flips this way, you may cast spells from your hand this turn without paying their mana costs.
        this.addAbility(new AttacksTriggeredAbility(new YusriFortunesFlameEffect(), false));
    }

    private YusriFortunesFlame(final YusriFortunesFlame card) {
        super(card);
    }

    @Override
    public YusriFortunesFlame copy() {
        return new YusriFortunesFlame(this);
    }
}

class YusriFortunesFlameEffect extends OneShotEffect {

    YusriFortunesFlameEffect() {
        super(Outcome.Benefit);
        staticText = "choose a number between 1 and 5. Flip that many coins. For each flip you win, draw a card. " +
                "For each flip you lose, {this} deals 2 damage to you. If you won five flips this way, " +
                "you may cast spells from your hand this turn without paying their mana costs";
    }

    private YusriFortunesFlameEffect(final YusriFortunesFlameEffect effect) {
        super(effect);
    }

    @Override
    public YusriFortunesFlameEffect copy() {
        return new YusriFortunesFlameEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(source.getControllerId());
        if (player == null) {
            return false;
        }
        // AI hint
        int flips = player.isComputer()
                ? chooseNumberAI(player, game)
                : player.getAmount(1, 5, "Choose a number between 1 and 5", source, game);
        int wins = player
                .flipCoins(source, game, flips, true)
                .stream()
                .mapToInt(x -> x ? 1 : 0)
                .sum();
        int losses = flips - wins;
        player.drawCards(wins, source, game);
        player.damage(2 * losses, source.getSourceId(), source, game);
        if (wins >= 5) {
            game.addEffect(new CastFromHandWithoutPayingManaCostEffect().setDuration(Duration.EndOfTurn), source);
        }
        return true;
    }

    /**
     * Each flip is on average half a card for 1 damage. 2 flips is the usual pick (at worst 4 damage); 1 at 10 life
     * or less; all 5 only when far ahead on life (at least 20, and 10 more than every opponent) with at least two
     * expensive spells in hand for the free-casting turn. Never more flips than cards in its library.
     */
    static int chooseNumberAI(Player player, Game game) {
        int life = player.getLife();
        int flips;
        if (life <= 10) {
            flips = 1;
        } else if (life >= 20 && isFarAhead(player, game) && countExpensiveSpells(player, game) >= 2) {
            flips = 5;
        } else {
            flips = 2;
        }
        return Math.max(1, Math.min(flips, player.getLibrary().size()));
    }

    private static boolean isFarAhead(Player player, Game game) {
        for (UUID opponentId : game.getOpponents(player.getId())) {
            Player opponent = game.getPlayer(opponentId);
            if (opponent != null && opponent.getLife() + 10 > player.getLife()) {
                return false;
            }
        }
        return true;
    }

    private static long countExpensiveSpells(Player player, Game game) {
        return player.getHand().getCards(game).stream()
                .filter(card -> !card.isLand(game) && card.getManaValue() >= 4)
                .count();
    }
}
