package mage.abilities.keyword;

import mage.abilities.Ability;
import mage.abilities.DelayedTriggeredAbility;
import mage.abilities.SpellAbility;
import mage.abilities.StaticAbility;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.condition.Condition;
import mage.abilities.costs.*;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenCopyTargetEffect;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.targetpointer.FixedTarget;
import mage.util.CardUtil;

import java.util.UUID;

/**
 * @author TheElk801
 */
public class OffspringAbility extends StaticAbility implements OptionalAdditionalSourceCosts {

    private static final String keywordText = "Offspring";
    private static final String reminderText = "You may pay an additional %s as you cast this spell. If you do, when this creature enters, create a 1/1 token copy of it.";
    private final String rule;

    public static final String OFFSPRING_ACTIVATION_VALUE_KEY = "offspringActivation";

    protected OptionalAdditionalCost additionalCost;

    public OffspringAbility(String manaString) {
        this(new ManaCostsImpl<>(manaString));
    }

    public OffspringAbility(Cost cost) {
        this(cost, true);
    }

    /**
     * @param addEntersTrigger printed offspring carries its "when this creature enters" trigger as a
     *                         sub-ability of the card. Offspring granted while casting (example:
     *                         {@link mage.abilities.effects.common.continuous.GainOffspringAbilityEffect})
     *                         is only on the card until it leaves the stack, so it passes false and
     *                         uses {@link #addOffspringTriggeredAbility} instead.
     */
    protected OffspringAbility(Cost cost, boolean addEntersTrigger) {
        super(Zone.STACK, null);
        this.additionalCost = new OptionalAdditionalCostImpl(
                keywordText + ' ' + cost.getText(),
                String.format(reminderText, cost.getText()), cost
        );
        this.additionalCost.setRepeatable(false);
        this.rule = additionalCost.getName() + ' ' + additionalCost.getReminderText();
        this.setRuleAtTheTop(true);
        if (addEntersTrigger) {
            this.addSubAbility(new EntersBattlefieldTriggeredAbility(new OffspringEffect())
                    .withInterveningIf(OffspringCondition.instance).setRuleVisible(false));
        }
    }

    protected OffspringAbility(final OffspringAbility ability) {
        super(ability);
        this.rule = ability.rule;
        this.additionalCost = ability.additionalCost.copy();
    }

    @Override
    public OffspringAbility copy() {
        return new OffspringAbility(this);
    }

    @Override
    public void addOptionalAdditionalCosts(Ability ability, Game game) {
        if (!(ability instanceof SpellAbility)) {
            return;
        }
        Player player = game.getPlayer(ability.getControllerId());
        if (player == null) {
            return;
        }
        additionalCost.reset();
        if (!additionalCost.canPay(ability, this, ability.getControllerId(), game)
                || !player.chooseUse(Outcome.PutCreatureInPlay, "Pay " + additionalCost.getText(true) + " for offspring?", ability, game)) {
            return;
        }
        additionalCost.activate();
        ability.addCost(additionalCost.copy());
        ability.setCostsTag(OFFSPRING_ACTIVATION_VALUE_KEY, null);
    }

    @Override
    public String getCastMessageSuffix() {
        return additionalCost.getCastSuffixMessage(0);
    }

    @Override
    public String getRule() {
        return rule;
    }

    /**
     * The token copy for offspring granted as the spell is cast: the granting object is not the
     * card, so the trigger cannot live on the card, and a delayed trigger on the spell is used.
     */
    protected void addOffspringTriggeredAbility(Game game, Ability source) {
        game.addDelayedTriggeredAbility(new OffspringDelayedTriggeredAbility(), source);
    }
}

class OffspringDelayedTriggeredAbility extends DelayedTriggeredAbility {

    OffspringDelayedTriggeredAbility() {
        super(new OffspringDelayedEffect(), Duration.Custom, true);
        setTriggerPhrase("When this creature enters, ");
    }

    private OffspringDelayedTriggeredAbility(final OffspringDelayedTriggeredAbility ability) {
        super(ability);
    }

    @Override
    public OffspringDelayedTriggeredAbility copy() {
        return new OffspringDelayedTriggeredAbility(this);
    }

    @Override
    public boolean checkEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.ENTERS_THE_BATTLEFIELD;
    }

    @Override
    public boolean checkTrigger(GameEvent event, Game game) {
        if (!event.getTargetId().equals(getSourceId())) {
            return false;
        }
        // the permanent that entered, not the spell the delayed trigger was made from
        getEffects().setTargetPointer(new FixedTarget(event.getTargetId(), game));
        return true;
    }

    @Override
    public boolean isInactive(Game game) {
        return super.isInactive(game)
                || game.getStack().getSpell(getSourceId()) == null
                && game.getPermanent(getSourceId()) == null;
    }
}

class OffspringEffect extends OneShotEffect {

    OffspringEffect() {
        super(Outcome.Benefit);
        staticText = "create a 1/1 token copy of it";
    }

    private OffspringEffect(final OffspringEffect effect) {
        super(effect);
    }

    @Override
    public OffspringEffect copy() {
        return new OffspringEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent permanent = source.getSourcePermanentOrLKI(game);
        return permanent != null && new CreateTokenCopyTargetEffect(
                null, null, false, 1, false,
                false, null, 1, 1, false
        ).setSavedPermanent(permanent).apply(game, source);
    }
}

enum OffspringCondition implements Condition {
    instance;

    @Override
    public boolean apply(Game game, Ability source) {
        return CardUtil.checkSourceCostsTagExists(game, source, OffspringAbility.OFFSPRING_ACTIVATION_VALUE_KEY);
    }

    @Override
    public String toString() {
        return "its offspring cost was paid";
    }
}

class OffspringDelayedEffect extends OneShotEffect {

    OffspringDelayedEffect() {
        super(Outcome.Benefit);
        staticText = "create a 1/1 token copy of it";
    }

    private OffspringDelayedEffect(final OffspringDelayedEffect effect) {
        super(effect);
    }

    @Override
    public OffspringDelayedEffect copy() {
        return new OffspringDelayedEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        UUID permanentId = getTargetPointer().getFirst(game, source);
        if (permanentId == null) {
            return false;
        }
        Permanent permanent = game.getPermanent(permanentId);
        if (permanent == null) {
            permanent = (Permanent) game.getLastKnownInformation(permanentId, Zone.BATTLEFIELD);
        }
        return permanent != null && new CreateTokenCopyTargetEffect(
                null, null, false, 1, false,
                false, null, 1, 1, false
        ).setSavedPermanent(permanent).apply(game, source);
    }
}
