package mage.player.seat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The engine as a local service: one JVM, many games, newline-delimited JSON
 * over a loopback socket. Every request carries an id that its response
 * echoes; requests on one connection are handled concurrently, so a
 * next_decision that blocks for one seat never holds up another.
 *
 * Commands: ping, create_game, next_decision, choose_action, state, rollback,
 * concede, set, end_game. See docs/rearch.md in fullpod for the shapes.
 */
public final class SeatHost {

    private static final Logger LOG = Logger.getLogger(SeatHost.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final java.lang.reflect.Type MAP = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final Map<String, GameHost> games = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "seat-host-worker");
        t.setDaemon(true);
        return t;
    });
    private final ServerSocket server;

    public SeatHost(int port) throws IOException {
        server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
    }

    public int port() {
        return server.getLocalPort();
    }

    public static void main(String[] args) throws Exception {
        int port = 0;
        boolean scanOnly = false;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
            }
            if ("--scan-only".equals(args[i])) {
                scanOnly = true; // build the card DB in ./db and exit (image builds, a warm first boot)
            }
        }
        long t0 = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        LOG.info("cards scanned in " + (System.currentTimeMillis() - t0) + " ms" + (errors.isEmpty() ? "" : "; " + errors.size() + " errors"));
        if (scanOnly) {
            System.out.println("SEATHOST_SCANNED=" + (System.currentTimeMillis() - t0));
            System.exit(errors.isEmpty() ? 0 : 1);
        }
        GameHost.initCollectors();
        SeatHost host = new SeatHost(port);
        // The line the launcher waits for.
        System.out.println("SEATHOST_PORT=" + host.port());
        System.out.flush();
        host.serve();
    }

    /** Accepts connections until the socket is closed. */
    public void serve() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread t = new Thread(() -> connection(socket), "seat-host-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!server.isClosed()) {
                    LOG.error("accept failed", e);
                }
            }
        }
    }

    public void close() throws IOException {
        server.close();
        for (GameHost g : games.values()) {
            g.end();
        }
        workers.shutdownNow();
    }

    private void connection(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), false)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final String request = line;
                workers.submit(() -> {
                    Map<String, Object> response = handleLine(request);
                    String json = GSON.toJson(response);
                    synchronized (out) {
                        out.println(json);
                        out.flush();
                    }
                });
            }
        } catch (IOException e) {
            LOG.debug("connection closed: " + e);
        }
    }

    Map<String, Object> handleLine(String line) {
        Object id = null;
        try {
            JsonObject req = JsonParser.parseString(line).getAsJsonObject();
            JsonElement idEl = req.get("id");
            id = idEl == null || idEl.isJsonNull() ? null : (idEl.isJsonPrimitive() && idEl.getAsJsonPrimitive().isNumber() ? idEl.getAsNumber() : idEl.getAsString());
            Map<String, Object> args = GSON.fromJson(req, MAP);
            Map<String, Object> result = handle(String.valueOf(args.get("cmd")), args);
            result.putIfAbsent("ok", true);
            result.put("id", id);
            return result;
        } catch (Exception e) {
            LOG.warn("request failed: " + line, e);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", false);
            r.put("error", String.valueOf(e.getMessage() != null ? e.getMessage() : e));
            r.put("id", id);
            return r;
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> handle(String cmd, Map<String, Object> args) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        switch (cmd) {
            case "ping" -> r.put("games", games.size());
            case "create_game" -> {
                String gameId = String.valueOf(args.getOrDefault("game_id", java.util.UUID.randomUUID().toString()));
                List<GameHost.SeatSpec> seats = new ArrayList<>();
                for (Map<String, Object> s : (List<Map<String, Object>>) args.get("seats")) {
                    seats.add(new GameHost.SeatSpec(String.valueOf(s.get("name")), String.valueOf(s.getOrDefault("kind", "seat")),
                            String.valueOf(s.get("deck")), s.get("skill") == null ? 0 : ((Number) s.get("skill")).intValue()));
                }
                Object seed = args.get("seed");
                GameHost.Config cfg = new GameHost.Config(gameId, String.valueOf(args.getOrDefault("format", "duel")),
                        seed == null ? null : ((Number) seed).longValue(),
                        args.get("game_log_dir") == null ? null : String.valueOf(args.get("game_log_dir")),
                        seats, Boolean.TRUE.equals(args.get("offer_mana_sources")),
                        args.get("replay_from") == null ? null : String.valueOf(args.get("replay_from")),
                        args.get("hold_through_seq") == null ? 0 : ((Number) args.get("hold_through_seq")).intValue(),
                        args.get("free_mulligans") == null ? 0 : ((Number) args.get("free_mulligans")).intValue(),
                        args.get("starting_player") == null ? "host" : String.valueOf(args.get("starting_player")));
                GameHost host = new GameHost(cfg);
                games.put(gameId, host);
                host.start();
                r.put("game_id", gameId);
                r.put("seats", host.seatNames());
            }
            case "next_decision" -> {
                long timeout = args.get("timeout_ms") == null ? 30_000 : ((Number) args.get("timeout_ms")).longValue();
                r.putAll(game(args).awaitDecision(seat(args), timeout));
            }
            case "choose_action" -> r.putAll(game(args).chooseAction(seat(args), args));
            case "state" -> r.putAll(game(args).state(seat(args)));
            case "rollback" -> r.put("success", game(args).rollback(seat(args), ((Number) args.getOrDefault("turns", 0)).intValue()));
            case "concede" -> game(args).concede(seat(args));
            case "set" -> {
                GameHost host = game(args);
                if (args.get("offer_mana_sources") != null) {
                    host.setOfferManaSources(seat(args), Boolean.TRUE.equals(args.get("offer_mana_sources")));
                }
                if (args.get("auto_pay") != null) {
                    host.setAutoPay(seat(args), Boolean.TRUE.equals(args.get("auto_pay")));
                }
                if (args.get("full_control") != null) {
                    host.setFullControl(seat(args), Boolean.TRUE.equals(args.get("full_control")));
                }
            }
            case "end_game" -> {
                GameHost host = games.remove(String.valueOf(args.get("game_id")));
                if (host != null) {
                    host.end();
                }
                r.put("ended", host != null);
            }
            default -> throw new IllegalArgumentException("unknown cmd: " + cmd);
        }
        return r;
    }

    private GameHost game(Map<String, Object> args) {
        GameHost host = games.get(String.valueOf(args.get("game_id")));
        if (host == null) {
            throw new IllegalArgumentException("no such game: " + args.get("game_id"));
        }
        return host;
    }

    private static String seat(Map<String, Object> args) {
        Object seat = args.get("seat");
        if (seat == null) {
            throw new IllegalArgumentException("seat is required");
        }
        return String.valueOf(seat);
    }
}
