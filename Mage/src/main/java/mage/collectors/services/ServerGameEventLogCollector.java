package mage.collectors.services;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.cards.Card;
import mage.choices.Choice;
import mage.constants.CardType;
import mage.constants.ManaType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.TurnPhase;
import mage.counters.Counter;
import mage.counters.Counters;
import mage.designations.DesignationType;
import mage.game.Game;
import mage.game.GameState;
import mage.game.combat.CombatGroup;
import mage.game.events.PlayerQueryEvent;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentToken;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.players.ManaPool;
import mage.players.Player;
import mage.target.Target;
import mage.util.MultiAmountMessage;
import mage.util.ShortIdRegistry;
import org.apache.log4j.Logger;
import org.jsoup.Jsoup;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side game event log collector. Writes deterministic JSONL to
 * server_game_events.jsonl in the game log directory.
 *
 * Events: game_start, game_action, decision, game_end.
 * Decision events are query+response pairs: onPlayerQuery buffers the query,
 * onPlayerResponse combines and writes the full decision.
 */
public class ServerGameEventLogCollector extends EmptyDataCollector {

    private static final Logger logger = Logger.getLogger(ServerGameEventLogCollector.class);
    public static final String SERVICE_CODE = "serverGameEventLog";
    private static final String FILE_NAME = "server_game_events.jsonl";

    // Per-game writer, synchronized for thread safety between game thread and network thread
    private final Map<UUID, GameEventLogger> loggers = new ConcurrentHashMap<>();

    @Override
    public String getServiceCode() {
        return SERVICE_CODE;
    }

    @Override
    public String getInitInfo() {
        return "server-side game event log";
    }

    @Override
    public void onGameStart(Game game) {
        String gameLogDir = game.getOptions().gameLogDir;
        if (gameLogDir == null) {
            return;
        }
        GameEventLogger gel = new GameEventLogger(game.getId(), gameLogDir);
        loggers.put(game.getId(), gel);

        // Pre-assign short IDs to player UUIDs in sorted name order.
        // Player UUIDs appear as targets in early events (e.g. "Select a starting player")
        // and the assignment order must be deterministic regardless of join order.
        ShortIdRegistry registry = game.getShortIdRegistry();
        List<Player> sortedPlayers = new ArrayList<>(game.getPlayers().values());
        sortedPlayers.sort(Comparator.comparing(Player::getName));
        for (Player player : sortedPlayers) {
            registry.getOrAssign(player.getId());
        }

        // Write game_start event
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", 0);
        event.put("type", "game_start");

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : sortedPlayers) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", player.getName());
            players.add(p);
        }
        event.put("players", players);
        gel.writeLine(toJson(event));
    }

    @Override
    public void onGameLog(Game game, String message, int gameSeq) {
        GameEventLogger gel = loggers.get(game.getId());
        if (gel == null) {
            return;
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", gameSeq);
        event.put("type", "game_action");
        event.put("message", stripHtml(message));
        gel.writeLine(toJson(event));

        // Emit turn_change / phase_change events when game state advances
        GameState state = game.getState();
        if (state == null) return;
        int turn = state.getTurnNum();
        TurnPhase phase = state.getTurnPhaseType();
        PhaseStep step = state.getTurnStepType();

        if (turn != gel.lastTurn || phase != gel.lastPhase || step != gel.lastStep) {
            Player active = state.getActivePlayerId() != null ? game.getPlayer(state.getActivePlayerId()) : null;
            String activeName = active != null ? active.getName() : null;

            // Emit turn_change when the turn number advances
            if (turn != gel.lastTurn) {
                Map<String, Object> turnEvent = new LinkedHashMap<>();
                turnEvent.put("seq", game.nextGameSeq());
                turnEvent.put("type", "turn_change");
                turnEvent.put("turn", turn);
                turnEvent.put("active_player", activeName);
                gel.writeLine(toJson(turnEvent));
            }

            Map<String, Object> phaseEvent = new LinkedHashMap<>();
            phaseEvent.put("seq", game.nextGameSeq());
            phaseEvent.put("type", "phase_change");
            phaseEvent.put("turn", turn);
            phaseEvent.put("phase", phase != null ? phase.name() : null);
            phaseEvent.put("step", step != null ? step.name() : null);
            phaseEvent.put("active_player", activeName);
            gel.writeLine(toJson(phaseEvent));
            gel.lastTurn = turn;
            gel.lastPhase = phase;
            gel.lastStep = step;
        }
    }

    @Override
    public void onPlayerQuery(Game game, PlayerQueryEvent queryEvent, int gameSeq) {
        GameEventLogger gel = loggers.get(game.getId());
        if (gel == null) {
            return;
        }

        // Skip non-decision event types
        PlayerQueryEvent.QueryType qt = queryEvent.getQueryType();
        if (qt == PlayerQueryEvent.QueryType.PERSONAL_MESSAGE
                || qt == PlayerQueryEvent.QueryType.TOURNAMENT_CONSTRUCT
                || qt == PlayerQueryEvent.QueryType.DRAFT_PICK_CARD) {
            return;
        }

        // Buffer pending query for this player
        PendingQuery pending = new PendingQuery();
        pending.gameSeq = gameSeq;
        pending.queryType = qt;
        pending.playerId = queryEvent.getPlayerId();
        pending.message = queryEvent.getMessage();
        pending.event = queryEvent;
        gel.setPendingQuery(queryEvent.getPlayerId(), pending);
    }

    @Override
    public void onPlayerResponse(Game game, UUID playerId, String responseType, Object data) {
        GameEventLogger gel = loggers.get(game.getId());
        if (gel == null) {
            return;
        }

        PendingQuery pending = gel.consumePendingQuery(playerId);
        if (pending == null) {
            // Response without a pending query — can happen for computer players
            return;
        }

        // Build decision event
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", pending.gameSeq);
        event.put("type", "decision");
        event.put("query_type", pending.queryType.name());

        ShortIdRegistry registry = game.getShortIdRegistry();
        Player player = game.getPlayer(playerId);
        event.put("player", player != null ? player.getName() : playerId.toString());

        if (pending.message != null) {
            event.put("message", stripHtml(pending.message));
        }

        // Build choices structure
        Map<String, Object> choices = buildChoices(game, pending);
        if (choices != null && !choices.isEmpty()) {
            event.put("choices", choices);
        }

        // Build response structure
        Map<String, Object> response = buildResponse(game, responseType, data, pending);
        event.put("response", response);

        gel.writeLine(toJson(event));
    }

    @Override
    public void onGameEnd(Game game) {
        GameEventLogger gel = loggers.get(game.getId());
        if (gel == null) {
            return;
        }

        int seq = game.nextGameSeq();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", seq);
        event.put("type", "game_end");

        // Find winner and life totals in a single pass.
        // hasWon() is only set for explicit "win the game" effects.
        // Most games end via a player losing (life <= 0, decking, etc.),
        // so fall back to: if exactly one player hasn't lost, they won.
        String winnerName = null;
        String survivor = null;
        int survivorCount = 0;
        Map<String, Integer> lifeTotals = new LinkedHashMap<>();
        for (Player p : game.getPlayers().values()) {
            lifeTotals.put(p.getName(), p.getLife());
            if (p.hasWon()) {
                winnerName = p.getName();
            } else if (!p.hasLost() && !p.hasLeft()) {
                survivor = p.getName();
                survivorCount++;
            }
        }
        if (winnerName == null && survivorCount == 1) {
            winnerName = survivor;
        }
        event.put("winner", winnerName);
        event.put("life_totals", lifeTotals);

        gel.writeLine(toJson(event));
        gel.close();
        loggers.remove(game.getId());
    }

    // --- Choices building per query type ---

    private Map<String, Object> buildChoices(Game game, PendingQuery pending) {
        Map<String, Object> choices = new LinkedHashMap<>();
        PlayerQueryEvent ev = pending.event;
        ShortIdRegistry registry = game.getShortIdRegistry();

        switch (pending.queryType) {
            case SELECT:
                // Playable objects available to play
                choices.put("can_pass", true);
                break;
            case ASK:
                choices.put("question", stripHtml(pending.message));
                break;
            case PICK_TARGET:
                if (ev.getTargets() != null) {
                    List<Map<String, Object>> targets = new ArrayList<>();
                    for (UUID targetId : ev.getTargets()) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("id", registry.getOrAssign(targetId));
                        MageObject obj = game.getObject(targetId);
                        t.put("name", obj != null ? obj.getName() : "Unknown");
                        targets.add(t);
                    }
                    choices.put("targets", targets);
                }
                choices.put("required", ev.isRequired());
                break;
            case CHOOSE_ABILITY:
                if (ev.getAbilities() != null) {
                    List<Map<String, Object>> abilities = new ArrayList<>();
                    int idx = 0;
                    for (Ability ab : ev.getAbilities()) {
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("index", idx++);
                        a.put("description", ab.getRule());
                        abilities.add(a);
                    }
                    choices.put("abilities", abilities);
                }
                break;
            case CHOOSE_CHOICE:
                if (ev.getChoice() != null) {
                    Choice c = ev.getChoice();
                    choices.put("options", new ArrayList<>(c.getChoices()));
                }
                break;
            case PLAY_MANA:
                choices.put("message", stripHtml(pending.message));
                break;
            case AMOUNT:
                choices.put("min", ev.getMin());
                choices.put("max", ev.getMax());
                break;
            case MULTI_AMOUNT:
                if (ev.getMessages() != null) {
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (MultiAmountMessage msg : ev.getMessages()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("description", msg.message);
                        item.put("min", msg.min);
                        item.put("max", msg.max);
                        items.add(item);
                    }
                    choices.put("items", items);
                }
                choices.put("total_min", ev.getMin());
                choices.put("total_max", ev.getMax());
                break;
            case CHOOSE_PILE:
                if (ev.getPile1() != null) {
                    choices.put("pile1", cardListToNames(ev.getPile1()));
                }
                if (ev.getPile2() != null) {
                    choices.put("pile2", cardListToNames(ev.getPile2()));
                }
                break;
            case CHOOSE_MODE:
                if (ev.getModes() != null) {
                    List<Map<String, Object>> modes = new ArrayList<>();
                    for (Map.Entry<UUID, String> entry : ev.getModes().entrySet()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("description", entry.getValue());
                        modes.add(m);
                    }
                    choices.put("modes", modes);
                }
                break;
            default:
                break;
        }
        return choices;
    }

    private List<Map<String, String>> cardListToNames(List<? extends Card> cards) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Card c : cards) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            result.add(m);
        }
        return result;
    }

    // --- Response building ---

    private Map<String, Object> buildResponse(Game game, String responseType, Object data, PendingQuery pending) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", responseType);
        ShortIdRegistry registry = game.getShortIdRegistry();

        switch (responseType) {
            case "uuid":
                UUID uuid = (UUID) data;
                if (uuid == null) {
                    response.put("type", "pass");
                } else {
                    response.put("id", registry.getOrAssign(uuid));
                    MageObject obj = game.getObject(uuid);
                    if (obj != null) {
                        response.put("name", obj.getName());
                    }
                    // An ability has no game object and gets its short id only
                    // here, at response time, so the id is not stable across
                    // runs; a resume replays it by content (ReplayFeederCollector).
                    if (pending.event != null && pending.event.getAbilities() != null) {
                        int idx = 0;
                        for (Ability ab : pending.event.getAbilities()) {
                            if (ab.getId().equals(uuid)) {
                                response.put("ability_index", idx);
                                response.put("name", ab.getRule());
                                break;
                            }
                            idx++;
                        }
                    }
                    // A mode is no game object either, and its id is new every
                    // game (Mode() draws a random UUID); so are the Done and
                    // Cancel entries' short ids. Record its position and text.
                    if (pending.event != null && pending.event.getModes() != null) {
                        int idx = 0;
                        for (Map.Entry<UUID, String> entry : pending.event.getModes().entrySet()) {
                            if (entry.getKey().equals(uuid)) {
                                response.put("mode_index", idx);
                                response.put("name", stripHtml(entry.getValue()));
                                break;
                            }
                            idx++;
                        }
                    }
                }
                break;
            case "boolean":
                response.put("value", data);
                break;
            case "string":
                response.put("value", data);
                break;
            case "integer":
                response.put("value", data);
                break;
            case "manaType":
                response.put("color", data != null ? data.toString() : null);
                break;
        }
        return response;
    }

    // --- State snapshot building ---







    // --- JSON serialization (simple, no dependency) ---

    private static String toJson(Object obj) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, obj);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJson(StringBuilder sb, Object obj) {
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof String) {
            sb.append('"');
            escapeJson(sb, (String) obj);
            sb.append('"');
        } else if (obj instanceof Number || obj instanceof Boolean) {
            sb.append(obj);
        } else if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            sb.append('{');
            boolean first = true;
            // Use sorted keys for deterministic output
            List<String> keys = new ArrayList<>(map.keySet());
            // Preserve insertion order for LinkedHashMap (don't sort)
            if (!(map instanceof LinkedHashMap)) {
                Collections.sort(keys);
            }
            for (String key : keys) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"');
                escapeJson(sb, key);
                sb.append("\":");
                appendJson(sb, map.get(key));
            }
            sb.append('}');
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                appendJson(sb, list.get(i));
            }
            sb.append(']');
        } else if (obj instanceof Enum) {
            sb.append('"');
            sb.append(obj.toString());
            sb.append('"');
        } else {
            sb.append('"');
            escapeJson(sb, obj.toString());
            sb.append('"');
        }
    }

    private static void escapeJson(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
    }

    private static String stripHtml(String html) {
        if (html == null) return null;
        return Jsoup.parse(html).text();
    }

    // --- Per-game logger ---

    private static class GameEventLogger {
        private final Path filePath;
        private BufferedWriter writer;
        // Pending queries per player (game thread writes, network thread reads)
        private final Map<UUID, PendingQuery> pendingQueries = new ConcurrentHashMap<>();
        // Phase tracking for phase_change events
        int lastTurn = -1;
        TurnPhase lastPhase = null;
        PhaseStep lastStep = null;

        GameEventLogger(UUID gameId, String gameLogDir) {
            this.filePath = Paths.get(gameLogDir, FILE_NAME);
            try {
                Files.createDirectories(filePath.getParent());
                this.writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.error("Failed to create server game event log: " + filePath, e);
                this.writer = null;
            }
        }

        synchronized void writeLine(String json) {
            if (writer == null) return;
            try {
                writer.write(json);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                logger.error("Failed to write to server game event log: " + filePath, e);
            }
        }


        void setPendingQuery(UUID playerId, PendingQuery query) {
            pendingQueries.put(playerId, query);
        }

        PendingQuery consumePendingQuery(UUID playerId) {
            return pendingQueries.remove(playerId);
        }

        synchronized void close() {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    logger.error("Failed to close server game event log: " + filePath, e);
                }
                writer = null;
            }
        }
    }

    // --- Pending query buffer ---

    private static class PendingQuery {
        int gameSeq;
        PlayerQueryEvent.QueryType queryType;
        UUID playerId;
        String message;
        PlayerQueryEvent event;
    }
}
