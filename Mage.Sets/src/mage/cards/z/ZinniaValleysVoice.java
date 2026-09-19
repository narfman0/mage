package mage.cards.z;

import mage.MageInt;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.dynamicvalue.DynamicValue;
import mage.abilities.dynamicvalue.common.PermanentsOnBattlefieldCount;
import mage.abilities.dynamicvalue.common.StaticValue;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.effects.common.continuous.BoostSourceEffect;
import mage.abilities.effects.common.continuous.GainOffspringAbilityEffect;
import mage.abilities.hint.ValueHint;
import mage.abilities.keyword.FlyingAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.ComparisonType;
import mage.constants.Duration;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.common.FilterControlledCreaturePermanent;
import mage.filter.common.FilterNonlandCard;
import mage.filter.predicate.mageobject.AnotherPredicate;
import mage.filter.predicate.mageobject.BasePowerPredicate;

import java.util.UUID;

/**
 * @author narfman0
 */
public final class ZinniaValleysVoice extends CardImpl {

    private static final FilterControlledCreaturePermanent boostFilter
            = new FilterControlledCreaturePermanent("other creatures you control with base power 1");
    private static final FilterNonlandCard offspringFilter
            = new FilterNonlandCard("creature spells you cast");

    static {
        boostFilter.add(AnotherPredicate.instance);
        boostFilter.add(new BasePowerPredicate(ComparisonType.EQUAL_TO, 1));
        offspringFilter.add(CardType.CREATURE.getPredicate());
    }

    private static final DynamicValue xValue = new PermanentsOnBattlefieldCount(boostFilter);

    public ZinniaValleysVoice(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{U}{R}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.BIRD);
        this.subtype.add(SubType.BARD);
        this.power = new MageInt(1);
        this.toughness = new MageInt(3);

        // Flying
        this.addAbility(FlyingAbility.getInstance());

        // Zinnia, Valley's Voice gets +X/+0, where X is the number of other creatures you control with base power 1.
        this.addAbility(new SimpleStaticAbility(new BoostSourceEffect(
                xValue, StaticValue.get(0), Duration.WhileOnBattlefield,
                "{this} gets +X/+0, where X is the number of other creatures you control with base power 1"
        )).addHint(new ValueHint("Other creatures you control with base power 1", xValue)));

        // Creature spells you cast gain offspring {2} as you cast them.
        this.addAbility(new SimpleStaticAbility(new GainOffspringAbilityEffect(
                new ManaCostsImpl<>("{2}"), offspringFilter,
                "creature spells you cast gain offspring {2} as you cast them. "
                        + "<i>(You may pay an additional {2} as you cast a creature spell. "
                        + "If you do, when that creature enters, create a 1/1 token copy of it.)</i>"
        )));
    }

    private ZinniaValleysVoice(final ZinniaValleysVoice card) {
        super(card);
    }

    @Override
    public ZinniaValleysVoice copy() {
        return new ZinniaValleysVoice(this);
    }
}
