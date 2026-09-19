package mage.cards.w;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.AttacksTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.MillCardsControllerEffect;
import mage.abilities.hint.HintUtils;
import mage.abilities.keyword.DeathtouchAbility;
import mage.abilities.triggers.BeginningOfEndStepTriggeredAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.AbilityWord;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.counters.Counters;
import mage.filter.FilterCard;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.players.Player;
import mage.target.TargetCard;
import mage.target.common.TargetCardInYourGraveyard;
import mage.util.CardUtil;

import java.awt.Color;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @author narfman0
 */
public final class WinterCynicalOpportunist extends CardImpl {

    public WinterCynicalOpportunist(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{B}{G}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.WARLOCK);
        this.power = new MageInt(2);
        this.toughness = new MageInt(5);

        // Deathtouch
        this.addAbility(DeathtouchAbility.getInstance());

        // Whenever Winter, Cynical Opportunist attacks, mill three cards.
        this.addAbility(new AttacksTriggeredAbility(new MillCardsControllerEffect(3)));

        // Delirium — At the beginning of your end step, you may exile any number of cards from your
        // graveyard with four or more card types among them. If you do, put a permanent card from
        // among them onto the battlefield with a finality counter on it.
        this.addAbility(new BeginningOfEndStepTriggeredAbility(
                new WinterCynicalOpportunistEffect()
        ).setAbilityWord(AbilityWord.DELIRIUM));
    }

    private WinterCynicalOpportunist(final WinterCynicalOpportunist card) {
        super(card);
    }

    @Override
    public WinterCynicalOpportunist copy() {
        return new WinterCynicalOpportunist(this);
    }
}

class WinterCynicalOpportunistEffect extends OneShotEffect {

    WinterCynicalOpportunistEffect() {
        super(Outcome.PutCardInPlay);
        staticText = "you may exile any number of cards from your graveyard with four or more card types "
                + "among them. If you do, put a permanent card from among them onto the battlefield with "
                + "a finality counter on it. <i>(If it would die, exile it instead.)</i>";
    }

    private WinterCynicalOpportunistEffect(final WinterCynicalOpportunistEffect effect) {
        super(effect);
    }

    @Override
    public WinterCynicalOpportunistEffect copy() {
        return new WinterCynicalOpportunistEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        WinterCynicalOpportunistTarget target = new WinterCynicalOpportunistTarget();
        if (!target.canChoose(controller.getId(), source, game)) {
            return false;
        }
        if (!controller.chooseUse(outcome, "Exile any number of cards from your graveyard "
                + "with four or more card types among them?", source, game)) {
            return false;
        }
        controller.choose(Outcome.Exile, target, source, game);
        Cards cards = new CardsImpl(target.getTargets());
        if (cards.isEmpty()) {
            return false;
        }
        controller.moveCards(cards, Zone.EXILED, source, game);
        Cards permanentCards = new CardsImpl(cards
                .getCards(game)
                .stream()
                .filter(card -> game.getState().getZone(card.getId()) == Zone.EXILED)
                .filter(card -> StaticFilters.FILTER_CARD_PERMANENT.match(card, game))
                .collect(Collectors.toSet()));
        if (permanentCards.isEmpty()) {
            return true;
        }
        TargetCard targetPermanent = new TargetCard(Zone.EXILED, StaticFilters.FILTER_CARD_PERMANENT);
        targetPermanent.withNotTarget(true);
        controller.choose(Outcome.PutCardInPlay, permanentCards, targetPermanent, source, game);
        Card card = game.getCard(targetPermanent.getFirstTarget());
        if (card == null) {
            return true;
        }
        Counters countersToAdd = new Counters();
        countersToAdd.addCounter(CounterType.FINALITY.createInstance());
        game.setEnterWithCounters(card.getId(), countersToAdd);
        controller.moveCards(card, Zone.BATTLEFIELD, source, game);
        return true;
    }
}

class WinterCynicalOpportunistTarget extends TargetCardInYourGraveyard {

    private static final FilterCard filter
            = new FilterCard("cards from your graveyard with four or more card types among them");

    WinterCynicalOpportunistTarget() {
        super(1, Integer.MAX_VALUE, filter, true);
    }

    private WinterCynicalOpportunistTarget(final WinterCynicalOpportunistTarget target) {
        super(target);
    }

    @Override
    public WinterCynicalOpportunistTarget copy() {
        return new WinterCynicalOpportunistTarget(this);
    }

    @Override
    public boolean isChosen(Game game) {
        return super.isChosen(game) && metCondition(this.getTargets(), game);
    }

    @Override
    public String getMessage(Game game) {
        Set<CardType> types = typesAmongSelection(this.getTargets(), game);
        String text = "Select " + CardUtil.addArticle(targetName);
        text += " (selected " + this.getTargets().size() + " cards; card types: ";
        text += HintUtils.prepareText(
                types.size() + " of 4",
                types.size() >= 4 ? Color.GREEN : Color.RED
        );
        String info = types.stream().map(CardType::toString).collect(Collectors.joining(", "));
        if (!info.isEmpty()) {
            text += " [" + info + "]";
        }
        text += ")";
        return text;
    }

    @Override
    public boolean canChoose(UUID sourceControllerId, Ability source, Game game) {
        if (!super.canChoose(sourceControllerId, source, game)) {
            return false;
        }
        // exiling every card that could be exiled must reach four card types, or there is nothing to do
        Set<UUID> idsToCheck = new HashSet<>(this.getTargets());
        idsToCheck.addAll(this.possibleTargets(sourceControllerId, source, game));
        return metCondition(idsToCheck, game);
    }

    private static Set<CardType> typesAmongSelection(Collection<UUID> cardsIds, Game game) {
        return cardsIds
                .stream()
                .map(game::getCard)
                .filter(Objects::nonNull)
                .flatMap(c -> c.getCardType(game).stream())
                .collect(Collectors.toSet());
    }

    private static boolean metCondition(Collection<UUID> cardsIds, Game game) {
        return typesAmongSelection(cardsIds, game).size() >= 4;
    }
}
