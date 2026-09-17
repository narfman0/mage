package mage.player.seat;

import mage.cards.Card;
import mage.constants.CardType;
import mage.constants.CommanderCardType;
import mage.constants.Zone;
import mage.game.Game;
import mage.players.Player;
import mage.util.ShortIdRegistry;
import mage.watchers.common.CommanderInfoWatcher;
import mage.watchers.common.CommanderPlaysCountWatcher;
import mage.view.AbilityView;
import mage.view.CardView;
import mage.view.CardsView;
import mage.view.CombatGroupView;
import mage.view.CommandObjectView;
import mage.view.CommanderView;
import mage.view.CounterView;
import mage.view.ExileView;
import mage.view.GameView;
import mage.view.ManaPoolView;
import mage.view.PermanentView;
import mage.view.PlayerView;
import mage.view.StackAbilityView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders engine views (GameView, CardView, ...) into the JSON shapes the
 * product already reads from mage-bench's bridge: the players array, stack
 * items, combat groups, target entries. Ported from BridgeGameStateBuilder,
 * BridgeCardFormatter and BridgeViewLocator (MIT, Gregor Stocks) with the
 * short-id source changed: in-process the game's own registry names objects,
 * so a view's short id and a registry lookup can never disagree.
 */
public final class Views {

    private final ShortIdRegistry shortIds;

    public Views(ShortIdRegistry shortIds) {
        this.shortIds = Objects.requireNonNull(shortIds);
    }

    public String shortId(UUID objectId) {
        return shortIds.getOrAssign(objectId);
    }

    public UUID resolve(String shortId) {
        return shortIds.tryResolve(shortId);
    }

    public int sequence(UUID objectId) {
        return shortIds.getSequence(objectId);
    }

    // ---- locating -------------------------------------------------------

    public CardView findCardView(UUID objectId, GameView gameView) {
        if (gameView == null || objectId == null) {
            return null;
        }
        CardView found = gameView.getMyHand().get(objectId);
        if (found != null) {
            return found;
        }
        found = gameView.getStack().get(objectId);
        if (found != null) {
            return found;
        }
        for (PlayerView player : gameView.getPlayers()) {
            PermanentView permanent = player.getBattlefield().get(objectId);
            if (permanent != null) {
                return permanent;
            }
            found = player.getGraveyard().get(objectId);
            if (found != null) {
                return found;
            }
            found = player.getExile().get(objectId);
            if (found != null) {
                return found;
            }
            for (CommandObjectView cmd : player.getCommandObjectList()) {
                if (cmd instanceof CommanderView cv && cmd.getId().equals(objectId)) {
                    return cv;
                }
            }
        }
        for (ExileView exileZone : gameView.getExile()) {
            for (CardView card : exileZone.values()) {
                if (card.getId().equals(objectId)) {
                    return card;
                }
            }
        }
        for (CardView card : gameView.getMyHand().values()) {
            CardView secondFace = card.getSecondCardFace();
            if (secondFace != null && secondFace.getId().equals(objectId)) {
                return secondFace;
            }
        }
        return null;
    }

    public PermanentView findPermanentView(UUID objectId, GameView gameView) {
        if (gameView == null) {
            return null;
        }
        for (PlayerView player : gameView.getPlayers()) {
            PermanentView perm = player.getBattlefield().get(objectId);
            if (perm != null) {
                return perm;
            }
        }
        return null;
    }

    // ---- names and cards ------------------------------------------------

    /** Ability views carry no name of their own; use their source card's. */
    public String displayName(CardView cv) {
        if (cv instanceof StackAbilityView sav) {
            String s = sourceCardName(sav.getSourceCard());
            if (s != null) {
                return s;
            }
        }
        if (cv instanceof AbilityView av) {
            String s = sourceCardName(av.getSourceCard());
            if (s != null) {
                return s;
            }
        }
        String name = cv.getDisplayName();
        if (name == null || name.isEmpty()) {
            name = cv.getName() != null ? cv.getName() : "Unknown";
        }
        return name;
    }

    private static String sourceCardName(CardView source) {
        if (source == null) {
            return null;
        }
        String name = source.getDisplayName();
        if (name == null || name.isEmpty()) {
            name = source.getName();
        }
        return (name == null || name.isEmpty()) ? null : name;
    }

    static List<String> cardTypeNames(CardView cv) {
        List<String> out = new ArrayList<>();
        for (CardType type : cv.getCardTypes()) {
            out.add(type.toString());
        }
        return out;
    }

    public Map<String, Object> cardInfo(CardView cv) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", displayName(cv));
        String manaCost = cv.getManaCostStr();
        if (manaCost != null && !manaCost.isEmpty()) {
            info.put("mana_cost", manaCost);
        }
        if (cv.isLand()) {
            info.put("is_land", true);
        }
        info.put("types", cardTypeNames(cv));
        if (cv.isCreature() && cv.getPower() != null) {
            info.put("power", cv.getPower());
            info.put("toughness", cv.getToughness());
        }
        List<String> rules = Fmt.stripHtmlList(cv.getRules());
        if (rules != null && !rules.isEmpty()) {
            info.put("rules", rules);
        }
        return info;
    }

    private String cardDescription(CardView cv) {
        String name = cv.getDisplayName();
        if (name == null) {
            name = cv.getName() != null ? cv.getName() : "Unknown";
        }
        StringBuilder sb = new StringBuilder(name);
        if (cv instanceof PermanentView pv) {
            if (pv.isCreature() && cv.getPower() != null && cv.getToughness() != null) {
                sb.append(" (").append(cv.getPower()).append("/").append(cv.getToughness()).append(")");
            }
            if (pv.isTapped()) {
                sb.append(" [tapped]");
            }
        }
        return sb.toString();
    }

    private String describeTarget(UUID targetId, GameView gameView, UUID myPlayerId) {
        CardView cv = findCardView(targetId, gameView);
        if (cv != null) {
            return cardDescription(cv) + controllerSuffix(targetId, gameView, myPlayerId);
        }
        if (gameView != null) {
            for (PlayerView player : gameView.getPlayers()) {
                if (player.getPlayerId().equals(targetId)) {
                    return player.getName() + (player.getPlayerId().equals(myPlayerId) ? " (you)" : "");
                }
            }
        }
        return "Unknown (" + targetId.toString().substring(0, 8) + ")";
    }

    private String controllerSuffix(UUID objectId, GameView gameView, UUID myPlayerId) {
        if (gameView == null || myPlayerId == null) {
            return "";
        }
        for (PlayerView player : gameView.getPlayers()) {
            if (player.getBattlefield().get(objectId) != null) {
                return player.getPlayerId().equals(myPlayerId) ? " (yours)" : " (" + player.getName() + "'s)";
            }
        }
        return "";
    }

    /**
     * Fills a target choice entry (name, target_type, power/toughness, tapped,
     * controller, is_you) and returns the card view it was based on, if any.
     */
    public CardView targetInfo(Map<String, Object> entry, UUID targetId, CardsView offered, GameView gameView, UUID myPlayerId) {
        CardView cv = offered != null ? offered.get(targetId) : null;
        if (cv == null) {
            cv = findCardView(targetId, gameView);
        }
        if (cv != null) {
            entry.put("name", displayName(cv));
            if (cv instanceof AbilityView) {
                // "Pick triggered ability": two triggers off one permanent share a
                // name; the rule text is what makes the choice answerable.
                entry.put("target_type", "ability");
                List<String> rules = Fmt.stripHtmlList(cv.getRules());
                if (rules != null && !rules.isEmpty()) {
                    entry.put("rules", rules);
                    entry.put("text", String.join(" ", rules));
                }
                return cv;
            }
            if (cv instanceof PermanentView pv) {
                entry.put("target_type", "permanent");
                if (pv.isCreature() && cv.getPower() != null) {
                    entry.put("power", cv.getPower());
                    entry.put("toughness", cv.getToughness());
                }
                if (pv.isTapped()) {
                    entry.put("tapped", true);
                }
            } else {
                entry.put("target_type", "card");
            }
            if (gameView != null) {
                for (PlayerView player : gameView.getPlayers()) {
                    if (player.getBattlefield().get(targetId) != null) {
                        if (!player.getPlayerId().equals(myPlayerId)) {
                            entry.put("controller", player.getName());
                        }
                        break;
                    }
                }
            }
            return cv;
        }
        if (gameView != null) {
            for (PlayerView player : gameView.getPlayers()) {
                if (player.getPlayerId().equals(targetId)) {
                    entry.put("name", player.getName());
                    entry.put("target_type", "player");
                    if (player.getPlayerId().equals(myPlayerId)) {
                        entry.put("is_you", true);
                    }
                    return null;
                }
            }
        }
        entry.put("name", "Unknown (" + targetId.toString().substring(0, 8) + ")");
        entry.put("target_type", "card");
        return null;
    }

    // ---- stack ----------------------------------------------------------

    public List<Map<String, Object>> stackItems(GameView gameView, UUID myPlayerId, boolean includeRules) {
        List<Map<String, Object>> stack = new ArrayList<>();
        if (gameView == null || gameView.getStack() == null) {
            return stack;
        }
        for (CardView card : gameView.getStack().values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (card.getId() != null) {
                item.put("id", shortId(card.getId()));
            }
            item.put("name", displayName(card));
            if (card instanceof StackAbilityView sav) {
                if (sav.getSourceCard() != null) {
                    item.put("source_card", displayName(sav.getSourceCard()));
                }
                List<String> rules = Fmt.stripHtmlList(card.getRules());
                if (rules != null && !rules.isEmpty()) {
                    item.put("ability_text", rules.get(0));
                }
            }
            if (includeRules) {
                item.put("rules", Fmt.stripHtmlList(card.getRules()));
            }
            if (card.getControllerId() != null) {
                String owner = gameView.getPlayerName(card.getControllerId());
                if (owner != null) {
                    item.put("owner", owner);
                }
            }
            if (card.getTargets() != null && !card.getTargets().isEmpty()) {
                List<Map<String, Object>> targets = new ArrayList<>();
                for (UUID targetId : card.getTargets()) {
                    Map<String, Object> t = new HashMap<>();
                    t.put("id", shortId(targetId));
                    t.put("name", describeTarget(targetId, gameView, myPlayerId));
                    targets.add(t);
                }
                item.put("targets", targets);
            }
            stack.add(item);
        }
        return stack;
    }

    // ---- board ----------------------------------------------------------

    public List<Map<String, Object>> players(GameView gameView, UUID myPlayerId) {
        return players(gameView, myPlayerId, null);
    }

    /**
     * The players array. With the game, each seat's command zone is rendered
     * as cards (where the commander is, its cast count and tax, the damage it
     * has dealt); without it, as names.
     */
    public List<Map<String, Object>> players(GameView gameView, UUID myPlayerId, Game game) {
        List<Map<String, Object>> players = new ArrayList<>();
        for (PlayerView player : stablePlayers(gameView, myPlayerId)) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", player.getName());
            info.put("life", player.getLife());
            info.put("library_size", player.getLibraryCount());
            info.put("hand_size", player.getHandCount());
            info.put("is_active", player.isActive());
            boolean isMe = player.getPlayerId().equals(myPlayerId);
            info.put("is_you", isMe);
            if (isMe && gameView.getMyHand() != null) {
                List<Map<String, Object>> hand = new ArrayList<>();
                var playable = gameView.getCanPlayObjects();
                List<Map.Entry<UUID, CardView>> sorted = new ArrayList<>(gameView.getMyHand().entrySet());
                sorted.sort(Comparator.<Map.Entry<UUID, CardView>, String>comparing(e -> displayName(e.getValue()))
                        .thenComparingInt(e -> sequence(e.getKey())));
                for (Map.Entry<UUID, CardView> e : sorted) {
                    Map<String, Object> card = cardInfo(e.getValue());
                    card.put("id", shortId(e.getKey()));
                    if (playable != null && playable.containsObject(e.getKey())) {
                        card.put("playable", true);
                    }
                    hand.add(card);
                }
                info.put("hand", hand);
            }
            List<Map<String, Object>> battlefield = new ArrayList<>();
            if (player.getBattlefield() != null) {
                List<PermanentView> sorted = new ArrayList<>(player.getBattlefield().values());
                sorted.sort(Comparator.<PermanentView, String>comparing(this::displayName).thenComparingInt(p -> sequence(p.getId())));
                for (PermanentView perm : sorted) {
                    battlefield.add(permanentInfo(perm));
                }
            }
            if (!battlefield.isEmpty()) {
                info.put("battlefield", battlefield);
            }
            List<Map<String, Object>> graveyard = zoneCards(player.getGraveyard());
            if (!graveyard.isEmpty()) {
                info.put("graveyard", graveyard);
            }
            List<Map<String, Object>> exile = zoneCards(player.getExile());
            if (!exile.isEmpty()) {
                info.put("exile", exile);
            }
            Map<String, Integer> mana = manaPool(player.getManaPool());
            if (!mana.isEmpty()) {
                info.put("mana_pool", mana);
            }
            if (player.getCounters() != null && !player.getCounters().isEmpty()) {
                Map<String, Integer> counters = new HashMap<>();
                for (CounterView c : player.getCounters()) {
                    counters.put(c.getName(), c.getCount());
                }
                info.put("counters", counters);
            }
            List<Object> commanders = commandZone(player, game);
            if (!commanders.isEmpty()) {
                info.put("commanders", commanders);
            }
            players.add(info);
        }
        return players;
    }

    /**
     * A seat's command zone. With the game: the player's commanders for the
     * whole game (the engine only keeps a command-zone object while the card
     * is physically there, so the view alone forgets a commander that is on
     * the battlefield), each as a card. Without it: the names in the view.
     */
    private List<Object> commandZone(PlayerView player, Game game) {
        List<Object> out = new ArrayList<>();
        Player p = game != null ? game.getPlayer(player.getPlayerId()) : null;
        if (p != null) {
            for (UUID id : game.getCommandersIds(p, CommanderCardType.COMMANDER_OR_OATHBREAKER, false)) {
                Card card = game.getCard(id);
                if (card != null) {
                    out.add(commanderInfo(card, game));
                }
            }
            return out;
        }
        if (player.getCommandObjectList() != null) {
            for (CommandObjectView cmd : player.getCommandObjectList()) {
                if (cmd instanceof CommanderView) {
                    out.add(cmd.getName());
                }
            }
        }
        return out;
    }

    /**
     * One commander for the board's command zone: the card (same short id it
     * keeps as a permanent, so a cast option's ref finds it wherever it is),
     * where it is right now, and the numbers a table shows next to it — how
     * often it has been cast from the command zone and the tax that makes,
     * and the damage it has dealt to each player (21 is lethal).
     */
    private Map<String, Object> commanderInfo(Card card, Game game) {
        Map<String, Object> info = cardInfo(new CardView(card, game));
        info.put("id", shortId(card.getId()));
        Zone zone = game.getState().getZone(card.getId());
        info.put("zone", zone == null ? "command" : zone.name().toLowerCase());
        CommanderPlaysCountWatcher plays = game.getState().getWatcher(CommanderPlaysCountWatcher.class);
        if (plays != null) {
            int count = plays.getPlaysCount(card.getId());
            info.put("cast_count", count);
            info.put("tax", 2 * count);
        }
        CommanderInfoWatcher damage = game.getState().getWatcher(CommanderInfoWatcher.class, card.getId());
        if (damage != null && !damage.getDamageToPlayer().isEmpty()) {
            Map<String, Integer> dealt = new LinkedHashMap<>();
            for (Map.Entry<UUID, Integer> e : damage.getDamageToPlayer().entrySet()) {
                Player p = game.getPlayer(e.getKey());
                dealt.put(p != null ? p.getName() : e.getKey().toString(), e.getValue());
            }
            info.put("damage_dealt", dealt);
        }
        return info;
    }

    private Map<String, Object> permanentInfo(PermanentView perm) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", shortId(perm.getId()));
        info.put("name", displayName(perm));
        info.put("tapped", perm.isTapped());
        // Current types (an animated land is a creature here) so a client can lay the board out in rows.
        info.put("types", cardTypeNames(perm));
        if (perm.isCreature()) {
            info.put("power", perm.getPower());
            info.put("toughness", perm.getToughness());
            info.put("summoning_sick", perm.hasSummoningSickness());
        }
        if (perm.isPlaneswalker()) {
            String loyalty = perm.getLoyalty();
            info.put("loyalty", loyalty.isEmpty() ? 0 : Integer.parseInt(loyalty));
        }
        if (perm.getCounters() != null && !perm.getCounters().isEmpty()) {
            Map<String, Integer> counters = new HashMap<>();
            for (CounterView c : perm.getCounters()) {
                counters.put(c.getName(), c.getCount());
            }
            info.put("counters", counters);
        }
        if (perm.isToken()) {
            info.put("token", true);
        }
        CardView orig = perm.getOriginal();
        if (orig != null && !Objects.equals(Fmt.stripHtmlList(perm.getRules()), Fmt.stripHtmlList(orig.getRules()))) {
            info.put("modified", true);
        }
        List<String> rules = Fmt.stripHtmlList(perm.getRules());
        if (rules != null && !rules.isEmpty()) {
            info.put("rules", rules);
        }
        String nameOwner = perm.getNameOwner();
        if (nameOwner != null && !nameOwner.isEmpty()) {
            info.put("owner", nameOwner);
        }
        if (perm.isAttachedToPermanent() && perm.getAttachedTo() != null) {
            info.put("attached_to", shortId(perm.getAttachedTo()));
        }
        if (perm.getAttachments() != null && !perm.getAttachments().isEmpty()) {
            List<String> attachments = new ArrayList<>();
            for (UUID id : perm.getAttachments()) {
                attachments.add(shortId(id));
            }
            info.put("attachments", attachments);
        }
        // Engine log lines name objects "Name [58c]": the first 3 chars of the UUID.
        info.put("uid", perm.getId().toString().substring(0, 3));
        String altName = perm.getAlternateName();
        if (altName != null && !altName.isEmpty()) {
            info.put("original_card", altName);
        }
        if (perm.isCopy()) {
            info.put("copy", true);
        }
        if (perm.isMorphed() || perm.isManifested()) {
            info.put("face_down", true);
        }
        return info;
    }

    private List<Map<String, Object>> zoneCards(CardsView zone) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (zone == null) {
            return out;
        }
        List<Map.Entry<UUID, CardView>> sorted = new ArrayList<>(zone.entrySet());
        sorted.sort(Comparator.<Map.Entry<UUID, CardView>, String>comparing(e -> displayName(e.getValue()))
                .thenComparingInt(e -> sequence(e.getKey())));
        for (Map.Entry<UUID, CardView> e : sorted) {
            Map<String, Object> card = new HashMap<>();
            card.put("id", shortId(e.getKey()));
            card.put("name", displayName(e.getValue()));
            List<String> rules = Fmt.stripHtmlList(e.getValue().getRules());
            if (rules != null && !rules.isEmpty()) {
                card.put("rules", rules);
            }
            out.add(card);
        }
        return out;
    }

    static Map<String, Integer> manaPool(ManaPoolView pool) {
        Map<String, Integer> mana = new HashMap<>();
        if (pool == null) {
            return mana;
        }
        if (pool.getRed() > 0) {
            mana.put("R", pool.getRed());
        }
        if (pool.getGreen() > 0) {
            mana.put("G", pool.getGreen());
        }
        if (pool.getBlue() > 0) {
            mana.put("U", pool.getBlue());
        }
        if (pool.getWhite() > 0) {
            mana.put("W", pool.getWhite());
        }
        if (pool.getBlack() > 0) {
            mana.put("B", pool.getBlack());
        }
        if (pool.getColorless() > 0) {
            mana.put("C", pool.getColorless());
        }
        return mana;
    }

    public List<Map<String, Object>> combatGroups(GameView gameView) {
        if (gameView == null || gameView.getCombat() == null || gameView.getCombat().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (CombatGroupView group : gameView.getCombat()) {
            Map<String, Object> g = new HashMap<>();
            g.put("attackers", creatureList(group.getAttackers().values()));
            List<Map<String, Object>> blockers = creatureList(group.getBlockers().values());
            if (!blockers.isEmpty()) {
                g.put("blockers", blockers);
            }
            g.put("blocked", group.isBlocked());
            g.put("defending", group.getDefenderName());
            groups.add(g);
        }
        return groups;
    }

    public List<Map<String, Object>> creatureList(Iterable<? extends CardView> cards) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CardView c : cards) {
            Map<String, Object> info = new HashMap<>();
            if (c.getId() != null) {
                info.put("id", shortId(c.getId()));
            }
            info.put("name", displayName(c));
            if (c.getPower() != null) {
                info.put("power", c.getPower());
                info.put("toughness", c.getToughness());
            }
            out.add(info);
        }
        return out;
    }

    private static List<PlayerView> stablePlayers(GameView gameView, UUID myPlayerId) {
        List<PlayerView> players = new ArrayList<>(gameView.getPlayers());
        players.sort((a, b) -> {
            boolean aIsYou = myPlayerId != null && myPlayerId.equals(a.getPlayerId());
            boolean bIsYou = myPlayerId != null && myPlayerId.equals(b.getPlayerId());
            int youCmp = Boolean.compare(bIsYou, aIsYou);
            if (youCmp != 0) {
                return youCmp;
            }
            int nameCmp = String.CASE_INSENSITIVE_ORDER.compare(
                    a.getName() != null ? a.getName() : "", b.getName() != null ? b.getName() : "");
            if (nameCmp != 0) {
                return nameCmp;
            }
            return a.getPlayerId().toString().compareTo(b.getPlayerId().toString());
        });
        return players;
    }
}
