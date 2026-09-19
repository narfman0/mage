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

    /**
     * The object behind a log ref — the {@code [a3f]} the engine appends to a
     * name in prompts and log lines, the first three hex digits of its UUID
     * (GameLog.getColoredObjectIdName). Found among the objects this game has
     * named: every deck card is registered at creation, a token or an
     * ability once the board or a choice has shown it.
     */
    public UUID byLogRef(String ref) {
        return byLogRef(ref, null, null);
    }

    /**
     * Three hex digits name one of 4096 buckets, and a pod's four decks are
     * ~400 objects, so two of them share a ref more often than not: with the
     * name beside the ref (the log name is "Pilgrim's Eye [a3f]") the object
     * so named wins; without it, only an unambiguous ref resolves. (A
     * four-seat golden replayed with the wrong source card, 2026-09-18.)
     */
    public UUID byLogRef(String ref, String name, Game game) {
        if (ref == null || ref.length() != 3) {
            return null;
        }
        List<UUID> matches = new ArrayList<>();
        for (String shortId : shortIds.snapshotShortIds()) {
            UUID id = shortIds.tryResolve(shortId);
            if (id != null && id.toString().startsWith(ref)) {
                matches.add(id);
            }
        }
        if (matches.size() > 1 && name != null && game != null) {
            List<UUID> named = new ArrayList<>();
            for (UUID id : matches) {
                mage.MageObject obj = game.getObject(id);
                Player player = obj == null ? game.getPlayer(id) : null;
                String n = obj != null ? obj.getName() : player != null ? player.getName() : null;
                if (name.equals(n)) {
                    named.add(id);
                }
            }
            matches = named;
        }
        return matches.size() == 1 ? matches.get(0) : null;
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
            if (player.getTopCard() != null && objectId.equals(player.getTopCard().getId())) {
                return player.getTopCard();
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
                if (cmd.getId().equals(objectId)) {
                    CardView asCard = commandObjectView(cmd);
                    if (asCard != null) {
                        return asCard;
                    }
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
        rulesAndHints(info, cv.getRules());
        return info;
    }

    /**
     * A card's {@code rules} (the printed markup, {@link Fmt#rules}) and,
     * apart from them, its {@code hints}: the engine's dynamic lines — "Cards
     * in your graveyard: 3", "Can't attack (Pacifism)", "Goaded by …" — each
     * {@code {text, kind?}} ({@link Fmt#splitRules}).
     */
    static void rulesAndHints(Map<String, Object> info, List<String> raw) {
        Fmt.Split split = Fmt.splitRules(raw);
        if (split.rules() != null && !split.rules().isEmpty()) {
            info.put("rules", split.rules());
        }
        if (!split.hints().isEmpty()) {
            info.put("hints", split.hints());
        }
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
                List<String> rules = Fmt.splitRules(cv.getRules()).rules();
                if (rules != null && !rules.isEmpty()) {
                    entry.put("rules", rules);
                    entry.put("text", String.join(" ", Fmt.stripHtmlList(rules)));
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
                item.put("rules", Fmt.splitRules(card.getRules()).rules());
            }
            Integer x = announcedX(card);
            if (x != null) {
                item.put("x", x);
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
        return players(gameView, myPlayerId, null, Map.of());
    }

    public List<Map<String, Object>> players(GameView gameView, UUID myPlayerId, Game game) {
        return players(gameView, myPlayerId, game, Map.of());
    }

    /**
     * The players array. With the game, each seat's command zone is rendered
     * as cards (where the commander is, its cast count and tax, the damage it
     * has dealt); without it, as names. {@code unlit} is why each of the
     * seat's own unplayable objects is unplayable ({@link Unlit}), empty
     * anywhere but a priority window.
     */
    public List<Map<String, Object>> players(GameView gameView, UUID myPlayerId, Game game, Map<UUID, Map<String, Object>> unlit) {
        List<Map<String, Object>> players = new ArrayList<>();
        List<PlayerView> ordered = game != null ? turnOrder(gameView, myPlayerId, game) : stablePlayers(gameView, myPlayerId);
        for (PlayerView player : ordered) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", player.getName());
            // Seats in turn order from this one (0 = the seat itself), so a
            // client lays the ring out and names the previous seat without
            // trusting the array's order.
            info.put("turn_order", players.size());
            info.put("life", player.getLife());
            info.put("library_size", player.getLibraryCount());
            info.put("hand_size", player.getHandCount());
            info.put("is_active", player.isActive());
            boolean isMe = player.getPlayerId().equals(myPlayerId);
            info.put("is_you", isMe);
            // The table state a seat tile shows next to life (the engine-UI
            // sweep, fullpod docs/engine-ui-surface.md, 2026-09-18): the
            // monarch and the initiative change hands on combat damage;
            // designations (the city's blessing) are permanent; the land-drop
            // count against its allowance (Azusa: 1 of 3); a seat that has lost
            // or left keeps a tile that says so.
            if (player.isMonarch()) {
                info.put("monarch", true);
            }
            if (player.isInitiative()) {
                info.put("initiative", true);
            }
            if (player.getDesignationNames() != null && !player.getDesignationNames().isEmpty()) {
                info.put("designations", new ArrayList<>(player.getDesignationNames()));
            }
            Map<String, Integer> drops = new LinkedHashMap<>();
            drops.put("used", player.getLandsPlayed());
            drops.put("per_turn", player.getLandsPerTurn());
            info.put("land_drops", drops);
            Player gp = game != null ? game.getPlayer(player.getPlayerId()) : null;
            if (player.hasLeft() || (gp != null && gp.hasLost())) {
                info.put("out", true);
            }
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
                    } else if (unlit.containsKey(e.getKey())) {
                        card.put("not_playable", unlit.get(e.getKey()));
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
                    Map<String, Object> entry = permanentInfo(perm, gameView);
                    Map<String, Object> why = unlit.get(perm.getId());
                    if (why != null) {
                        entry.put("not_playable", why);
                    }
                    battlefield.add(entry);
                }
            }
            if (!battlefield.isEmpty()) {
                info.put("battlefield", battlefield);
            }
            // A card castable from the graveyard or exile is lit there like a hand card.
            var playableNow = isMe ? gameView.getCanPlayObjects() : null;
            List<Map<String, Object>> graveyard = zoneCards(player.getGraveyard(), playableNow);
            if (!graveyard.isEmpty()) {
                info.put("graveyard", graveyard);
            }
            List<Map<String, Object>> exile = zoneCards(player.getExile(), playableNow);
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
            List<Object> commanders = commandZone(player, game, unlit);
            if (!commanders.isEmpty()) {
                info.put("commanders", commanders);
            }
            List<Map<String, Object>> objects = commandObjects(player);
            if (!objects.isEmpty()) {
                info.put("command_zone", objects);
            }
            // The top of a library that is public (Courser of Kruphix, Oracle of
            // Mul Daya): the card, with its id so a play from the top is a real
            // land/cast choice (the engine-UI sweep, fullpod
            // docs/engine-ui-surface.md, 2026-09-18).
            if (player.getTopCard() != null) {
                Map<String, Object> top = cardInfo(player.getTopCard());
                top.put("id", shortId(player.getTopCard().getId()));
                info.put("top_card", top);
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
    private List<Object> commandZone(PlayerView player, Game game, Map<UUID, Map<String, Object>> unlit) {
        List<Object> out = new ArrayList<>();
        Player p = game != null ? game.getPlayer(player.getPlayerId()) : null;
        if (p != null) {
            for (UUID id : game.getCommandersIds(p, CommanderCardType.COMMANDER_OR_OATHBREAKER, false)) {
                Card card = game.getCard(id);
                if (card != null) {
                    out.add(commanderInfo(card, game, unlit));
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
     * The command zone's non-commander objects: a planeswalker's emblem (a
     * permanent rule of the game with no card on the board to read it from),
     * a dungeon (venture: the current room is a hint), a plane. Each with
     * its rules and hints, and an id so an emblem's activated ability is a
     * choice with a ref (the engine-UI sweep, fullpod
     * docs/engine-ui-surface.md, 2026-09-18).
     */
    private List<Map<String, Object>> commandObjects(PlayerView player) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (player.getCommandObjectList() == null) {
            return out;
        }
        for (CommandObjectView cmd : player.getCommandObjectList()) {
            String kind;
            if (cmd instanceof mage.view.EmblemView) {
                kind = "emblem";
            } else if (cmd instanceof mage.view.DungeonView) {
                kind = "dungeon";
            } else if (cmd instanceof mage.view.PlaneView) {
                kind = "plane";
            } else {
                continue; // commanders: `commanders`, from the game
            }
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("kind", kind);
            info.put("id", shortId(cmd.getId()));
            info.put("name", Fmt.stripHtml(cmd.getName()));
            rulesAndHints(info, cmd.getRules());
            out.add(info);
        }
        return out;
    }

    /** A command-zone object other than a commander, as a card view (for a playable's name and choice). */
    private static CardView commandObjectView(CommandObjectView cmd) {
        if (cmd instanceof mage.view.EmblemView e) {
            return new CardView(e);
        }
        if (cmd instanceof mage.view.DungeonView d) {
            return new CardView(d);
        }
        if (cmd instanceof mage.view.PlaneView pl) {
            return new CardView(pl);
        }
        return null;
    }

    /**
     * One commander for the board's command zone: the card (same short id it
     * keeps as a permanent, so a cast option's ref finds it wherever it is),
     * where it is right now, and the numbers a table shows next to it — how
     * often it has been cast from the command zone and the tax that makes,
     * and the damage it has dealt to each player (21 is lethal) — and, while
     * it sits in the zone and the seat can't cast it, why ({@link Unlit},
     * with the tax already inside the cost the reason names).
     */
    private Map<String, Object> commanderInfo(Card card, Game game, Map<UUID, Map<String, Object>> unlit) {
        Map<String, Object> info = cardInfo(new CardView(card, game));
        info.put("id", shortId(card.getId()));
        Zone zone = game.getState().getZone(card.getId());
        info.put("zone", zone == null ? "command" : zone.name().toLowerCase());
        Map<String, Object> why = unlit.get(card.getId());
        if (why != null) {
            info.put("not_playable", why);
        }
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

    private Map<String, Object> permanentInfo(PermanentView perm, GameView gameView) {
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
            // A token's art is keyed by its *printed* power/toughness (a 3/3
            // and a 4/4 "Beast" are different prints), but `power`/`toughness`
            // above are the current values, so an anthem or a +1/+1 counter
            // would send the wrong signature. `getOriginal()` is the token as
            // created, which carries the printed pair.
            CardView asCreated = perm.getOriginal();
            if (perm.isCreature() && asCreated != null) {
                info.put("base_power", asCreated.getPower());
                info.put("base_toughness", asCreated.getToughness());
            }
        }
        // `original` is built without the game, so it never carries hints:
        // compare the rules alone, or every hinted permanent reads as modified.
        Fmt.Split split = Fmt.splitRules(perm.getRules());
        CardView orig = perm.getOriginal();
        if (orig != null && !Objects.equals(split.rules(), Fmt.splitRules(orig.getRules()).rules())) {
            info.put("modified", true);
        }
        if (split.rules() != null && !split.rules().isEmpty()) {
            info.put("rules", split.rules());
        }
        if (!split.hints().isEmpty()) {
            info.put("hints", split.hints());
        }
        String nameOwner = perm.getNameOwner();
        if (nameOwner != null && !nameOwner.isEmpty()) {
            info.put("owner", nameOwner);
        }
        if (perm.isAttachedToPermanent() && perm.getAttachedTo() != null) {
            info.put("attached_to", shortId(perm.getAttachedTo()));
            // An aura on a creature is shown on the *host's* controller's
            // battlefield (PlayerView.showInBattlefield), so an opponent's
            // Pacifism sits in your host group: say whose it is.
            if (perm.isAttachedToDifferentlyControlledPermanent() && perm.getNameController() != null) {
                info.put("controller", perm.getNameController());
            }
        } else if (perm.getAttachedTo() != null) {
            // A curse: attached to a player, shown on its controller's battlefield.
            String who = gameView != null ? gameView.getPlayerName(perm.getAttachedTo()) : null;
            if (who != null) {
                info.put("attached_to_player", who);
            }
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
        if (perm.isMorphed() || perm.isManifested() || perm.isDisguised() || perm.isCloaked()) {
            info.put("face_down", true);
            info.put("face_down_kind", perm.isMorphed() ? "morph" : perm.isManifested() ? "manifest" : perm.isDisguised() ? "disguise" : "cloak");
        }
        // State the board drops otherwise (the engine-UI sweep, fullpod
        // docs/engine-ui-surface.md, 2026-09-18): damage marked this turn,
        // a phased-out permanent (looks absent, controls nothing), and the X
        // announced for a permanent cast with one.
        if (perm.getDamage() > 0) {
            info.put("damage", perm.getDamage());
        }
        if (!perm.isPhasedIn()) {
            info.put("phased_out", true);
        }
        Integer x = announcedX(perm);
        if (x != null) {
            info.put("x", x);
        }
        return info;
    }

    /**
     * The X announced for a spell or ability, from the engine's own card icon
     * ("x=5"). Zero is left out: the icon reads 0 while X is still being
     * announced (the spell is on the stack already), and a spell for X=0 is
     * nothing the board needs a badge for.
     */
    static Integer announcedX(CardView cv) {
        if (cv.getCardIcons() == null) {
            return null;
        }
        for (mage.abilities.icon.CardIcon icon : cv.getCardIcons()) {
            if (icon.getIconType() == mage.abilities.icon.CardIconType.OTHER_COST_X && icon.getText().startsWith("x=")) {
                try {
                    int x = Integer.parseInt(icon.getText().substring(2));
                    return x > 0 ? x : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private List<Map<String, Object>> zoneCards(CardsView zone, mage.players.PlayableObjectsList playable) {
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
            if (playable != null && playable.containsObject(e.getKey())) {
                card.put("playable", true);
            }
            rulesAndHints(card, e.getValue().getRules());
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

    /**
     * The engine's revealed piles (a tutor's find, a revealed hand, Fact or
     * Fiction's five — public to everyone) and the piles this seat looked at
     * without a choice (Sensei's Divining Top, an opponent's hand via Peek),
     * each named by the engine, for as long as it keeps them.
     */
    public List<Map<String, Object>> revealed(GameView gameView, Game game) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (mage.view.RevealedView r : gameView.getRevealed()) {
            List<Map<String, Object>> cards = new ArrayList<>();
            for (Map.Entry<UUID, CardView> e : r.getCards().entrySet()) {
                Map<String, Object> card = cardInfo(e.getValue());
                card.put("id", shortId(e.getKey()));
                cards.add(card);
            }
            Map<String, Object> pile = new LinkedHashMap<>();
            pile.put("name", Fmt.stripHtml(r.getName()));
            pile.put("cards", cards);
            out.add(pile);
        }
        return out;
    }

    public List<Map<String, Object>> lookedAt(GameView gameView, Game game) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (mage.view.LookedAtView l : gameView.getLookedAt()) {
            List<Map<String, Object>> cards = new ArrayList<>();
            // A looked-at pile is ids and printings only (SimpleCardView); the
            // card itself is in the game.
            for (UUID id : l.getCards().keySet()) {
                Card card = game.getCard(id);
                if (card == null) {
                    continue;
                }
                Map<String, Object> info = cardInfo(new CardView(card, game));
                info.put("id", shortId(id));
                cards.add(info);
            }
            Map<String, Object> pile = new LinkedHashMap<>();
            pile.put("name", Fmt.stripHtml(l.getName()));
            pile.put("cards", cards);
            out.add(pile);
        }
        return out;
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

    /**
     * The seats in turn order, starting from this one: the game's player
     * list is the cycle the turns go round (a starting player only picks
     * where it begins), rotated so the seat itself is first. The list is a
     * CircularList that inserts at the front, so the cycle runs opposite to
     * seating order — seated A, B, C, D the turns go A, D, C, B; the
     * engine's quirk, and what the board must say. Before (2026-09-18) the
     * rest were sorted by engine username, so the board's ring and its
     * "previous seat" agreed with the table only by luck of the names.
     */
    private static List<PlayerView> turnOrder(GameView gameView, UUID myPlayerId, Game game) {
        List<UUID> cycle = new ArrayList<>(game.getState().getPlayerList());
        List<PlayerView> byId = new ArrayList<>(gameView.getPlayers());
        List<PlayerView> out = new ArrayList<>();
        int start = myPlayerId != null ? cycle.indexOf(myPlayerId) : -1;
        for (int i = 0; i < cycle.size(); i++) {
            UUID id = cycle.get((Math.max(start, 0) + i) % cycle.size());
            for (PlayerView pv : byId) {
                if (pv.getPlayerId().equals(id)) {
                    out.add(pv);
                    break;
                }
            }
        }
        // A view player the list doesn't know (never, but the board must not lose a seat).
        for (PlayerView pv : stablePlayers(gameView, myPlayerId)) {
            if (!out.contains(pv)) {
                out.add(pv);
            }
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
