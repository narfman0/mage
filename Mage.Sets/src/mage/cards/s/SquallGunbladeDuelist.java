package mage.cards.s;

import mage.MageInt;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.common.AsEntersBattlefieldAbility;
import mage.abilities.common.AttacksPlayerWithCreaturesTriggeredAbility;
import mage.abilities.condition.Condition;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.keyword.FirstStrikeAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.*;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.util.CardUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * @author TheElk801
 */
public final class SquallGunbladeDuelist extends CardImpl {

    public SquallGunbladeDuelist(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{R}{W}{B}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.MERCENARY);
        this.power = new MageInt(3);
        this.toughness = new MageInt(2);

        // First strike
        this.addAbility(FirstStrikeAbility.getInstance());

        // As Squall enters, choose a number.
        this.addAbility(new AsEntersBattlefieldAbility(new SquallGunbladeDuelistChooseEffect()));

        // Whenever one or more creatures attack one of your opponents, if any of those creatures have power or toughness equal to the chosen number, Squall deals damage equal to its power to defending player.
        this.addAbility(new AttacksPlayerWithCreaturesTriggeredAbility(
                new SquallGunbladeDuelistDamageEffect(), 1,
                StaticFilters.FILTER_PERMANENT_CREATURE, SetTargetPointer.PLAYER, true
        ).withInterveningIf(SquallGunbladeDuelistCondition.instance)
                .setTriggerPhrase("Whenever one or more creatures attack one of your opponents, "));
    }

    private SquallGunbladeDuelist(final SquallGunbladeDuelist card) {
        super(card);
    }

    @Override
    public SquallGunbladeDuelist copy() {
        return new SquallGunbladeDuelist(this);
    }
}

enum SquallGunbladeDuelistCondition implements Condition {
    instance;

    @Override
    public boolean apply(Game game, Ability source) {
        Integer number = (Integer) game
                .getState()
                .getValue(CardUtil.getObjectZoneString(
                        "chosenNumber", source.getSourceId(), game,
                        game.getState().getZoneChangeCounter(source.getSourceId()), true
                ));
        return source
                .getAllEffects()
                .stream()
                .map(effect -> (Set<Permanent>) effect.getValue("attackingCreatures"))
                .filter(Objects::nonNull)
                .findFirst()
                .map(Collection::stream)
                .filter(stream -> stream.anyMatch(
                        permanent -> permanent.getPower().getValue() == number
                                || permanent.getToughness().getValue() == number
                ))
                .isPresent();
    }

    @Override
    public String toString() {
        return "any of those creatures have power or toughness equal to the chosen number";
    }
}

class SquallGunbladeDuelistChooseEffect extends OneShotEffect {

    SquallGunbladeDuelistChooseEffect() {
        super(Outcome.Benefit);
        staticText = "choose a number";
    }

    private SquallGunbladeDuelistChooseEffect(final SquallGunbladeDuelistChooseEffect effect) {
        super(effect);
    }

    @Override
    public SquallGunbladeDuelistChooseEffect copy() {
        return new SquallGunbladeDuelistChooseEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(source.getControllerId());
        if (player == null) {
            return false;
        }
        // AI hint
        int number = player.isComputer()
                ? chooseNumberAI(player, source, game)
                : player.getAmount(0, Integer.MAX_VALUE, "Choose a number", source, game);
        game.getState().setValue(CardUtil.getObjectZoneString(
                "chosenNumber", source.getSourceId(), game,
                game.getState().getZoneChangeCounter(source.getSourceId()), false
        ), number);
        Permanent permanent = game.getPermanentEntering(source.getSourceId());
        if (permanent != null) {
            permanent.addInfo("chosen number", "<font color = 'blue'>Chosen Number: " + number + "</font>", game);
            game.informPlayers(permanent.getLogName() + ", chosen number: " + number);
        }
        return true;
    }

    /**
     * The power or toughness most common among its own creatures (they are the attackers that trigger it): those
     * on the battlefield, Squall itself and the creature cards in its hand, each counting a value once. Ties go to
     * the higher value.
     */
    static int chooseNumberAI(Player player, Ability source, Game game) {
        Map<Integer, Integer> seen = new HashMap<>();
        List<MageObject> creatures = new ArrayList<>();
        creatures.addAll(game.getBattlefield().getAllActivePermanents(
                StaticFilters.FILTER_PERMANENT_CREATURE, player.getId(), game));
        Permanent squall = game.getPermanentEntering(source.getSourceId());
        if (squall != null && creatures.stream().noneMatch(creature -> creature.getId().equals(squall.getId()))) {
            creatures.add(squall);
        }
        for (Card card : player.getHand().getCards(game)) {
            if (card.isCreature(game)) {
                creatures.add(card);
            }
        }
        for (MageObject creature : creatures) {
            int power = creature.getPower().getValue();
            int toughness = creature.getToughness().getValue();
            if (power > 0) {
                seen.merge(power, 1, Integer::sum);
            }
            if (toughness > 0 && toughness != power) {
                seen.merge(toughness, 1, Integer::sum);
            }
        }
        int best = 3;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> entry : seen.entrySet()) {
            if (entry.getValue() > bestCount || (entry.getValue() == bestCount && entry.getKey() > best)) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }
}

class SquallGunbladeDuelistDamageEffect extends OneShotEffect {

    SquallGunbladeDuelistDamageEffect() {
        super(Outcome.Benefit);
        staticText = "{this} deals damage equal to its power to defending player";
    }

    private SquallGunbladeDuelistDamageEffect(final SquallGunbladeDuelistDamageEffect effect) {
        super(effect);
    }

    @Override
    public SquallGunbladeDuelistDamageEffect copy() {
        return new SquallGunbladeDuelistDamageEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(getTargetPointer().getFirst(game, source));
        Permanent permanent = source.getSourcePermanentOrLKI(game);
        return player != null
                && permanent != null
                && player.damage(permanent.getPower().getValue(), permanent.getId(), source, game) > 0;
    }
}
