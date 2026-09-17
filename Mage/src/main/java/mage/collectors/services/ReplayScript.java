package mage.collectors.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A recorded server game event log (ServerGameEventLogCollector's
 * server_game_events.jsonl) read back as the things a resume has to
 * reproduce: every decision per player, in order, and every game log line.
 * <p>
 * Decisions are matched by player name and query type and answered by the
 * recorded response — for object choices the <em>short id</em> (stable by
 * construction: {@link mage.util.ShortIdRegistry}), never a position in a
 * list. Log lines are compared with their engine refs ({@code [a7c]}, a UUID
 * prefix that differs every run) stripped.
 */
public final class ReplayScript {

    /** One recorded decision. */
    public record Decision(int seq, String player, String queryType, String responseType,
                           String id, String name, JsonElement value, String color, Integer abilityIndex,
                           Integer modeIndex) {
    }

    private static final Pattern LOG_REF = Pattern.compile("\\s*\\[[0-9a-f]{3}\\]");

    private final Map<String, Deque<Decision>> byPlayer = new LinkedHashMap<>();
    private final Deque<String> logLines = new ArrayDeque<>();
    private final List<String> playerNames;
    private final int total;

    private ReplayScript(List<Decision> decisions, List<String> logs, List<String> playerNames) {
        for (Decision d : decisions) {
            byPlayer.computeIfAbsent(d.player(), k -> new ArrayDeque<>()).add(d);
        }
        logLines.addAll(logs);
        this.playerNames = List.copyOf(playerNames);
        total = decisions.size();
    }

    /** Player names as the record's game_start lists them (sorted by name —
     *  the order player short ids were assigned in). A resumed game's players
     *  may have new usernames (a pool hands out fresh ones); they map to these
     *  by position in the same sort. */
    public List<String> playerNames() {
        return playerNames;
    }

    public static ReplayScript load(Path path) throws IOException {
        return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    public static ReplayScript parse(List<String> lines) {
        List<Decision> decisions = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        List<String> players = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JsonObject e;
            try {
                e = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException ignored) {
                continue;
            }
            String type = str(e, "type");
            if ("decision".equals(type)) {
                JsonObject r = e.has("response") && e.get("response").isJsonObject() ? e.getAsJsonObject("response") : new JsonObject();
                decisions.add(new Decision(
                    e.has("seq") ? e.get("seq").getAsInt() : -1,
                    str(e, "player"),
                    str(e, "query_type"),
                    str(r, "type"),
                    str(r, "id"),
                    str(r, "name"),
                    r.get("value"),
                    str(r, "color"),
                    r.has("ability_index") && r.get("ability_index").isJsonPrimitive() ? r.get("ability_index").getAsInt() : null,
                    r.has("mode_index") && r.get("mode_index").isJsonPrimitive() ? r.get("mode_index").getAsInt() : null));
            } else if ("game_action".equals(type)) {
                logs.add(normalizeLog(str(e, "message")));
            } else if ("game_start".equals(type) && e.has("players") && e.get("players").isJsonArray()) {
                for (JsonElement pe : e.getAsJsonArray("players")) {
                    if (pe.isJsonObject() && pe.getAsJsonObject().has("name")) {
                        players.add(pe.getAsJsonObject().get("name").getAsString());
                    }
                }
            }
        }
        return new ReplayScript(decisions, logs, players);
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? null : v.isJsonPrimitive() ? v.getAsString() : v.toString();
    }

    /** A game log line as the record stores it (HTML stripped, like
     *  ServerGameEventLogCollector) and without its per-run engine refs.
     *  Whitespace runs collapse to one space whether or not the line went
     *  through Jsoup: the recorder's did, so "HAND:  (none)" and
     *  "HAND: (none)" are the same line. */
    public static String normalizeLog(String message) {
        if (message == null) {
            return "";
        }
        String text = message.indexOf('<') >= 0 ? org.jsoup.Jsoup.parse(message).text() : message;
        return LOG_REF.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    public int total() {
        return total;
    }

    public int remaining() {
        return byPlayer.values().stream().mapToInt(Deque::size).sum();
    }

    public boolean exhausted() {
        return remaining() == 0;
    }

    /** The next recorded decision for a player, or null when they have none left. */
    public Decision next(String player) {
        Deque<Decision> q = byPlayer.get(player);
        return q == null ? null : q.pollFirst();
    }

    public Decision peek(String player) {
        Deque<Decision> q = byPlayer.get(player);
        return q == null ? null : q.peekFirst();
    }

    /** The next expected (normalised) game log line, consumed; null when none are left. */
    public String nextLog() {
        return logLines.pollFirst();
    }

    public int logsRemaining() {
        return logLines.size();
    }
}
