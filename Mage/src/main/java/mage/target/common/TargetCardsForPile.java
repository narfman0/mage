package mage.target.common;

import mage.constants.Zone;
import mage.filter.FilterCard;
import mage.target.TargetCard;

/**
 * Chooses the cards for the first of two piles when a player separates cards into piles; the rest form the
 * second pile. Any number of cards may go in either pile, so a separating AI can recognise the choice by this
 * type and split the cards instead of reading the empty choice a min of zero allows.
 */
public class TargetCardsForPile extends TargetCard {

    public TargetCardsForPile(int numCards, Zone zone, FilterCard filter) {
        super(0, numCards, zone, filter);
    }

    protected TargetCardsForPile(final TargetCardsForPile target) {
        super(target);
    }

    @Override
    public TargetCardsForPile copy() {
        return new TargetCardsForPile(this);
    }
}
