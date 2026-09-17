package mage.player.seat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import mage.cards.repository.CardScanner;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The socket protocol end to end: create a game, take decisions, answer, end. */
public class SeatHostTest {

    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type MAP = new TypeToken<Map<String, Object>>() {
    }.getType();

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 120_000)
    public void jsonLinesRoundTrip() throws Exception {
        SeatHost host = new SeatHost(0);
        Thread serving = new Thread(host::serve, "test-seat-host");
        serving.setDaemon(true);
        serving.start();
        try (Socket socket = new Socket("127.0.0.1", host.port());
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            Map<String, Object> pong = call(out, in, "{\"id\":1,\"cmd\":\"ping\"}");
            Assert.assertEquals(true, pong.get("ok"));
            Assert.assertEquals(1.0, pong.get("id"));

            Map<String, Object> created = call(out, in, "{\"id\":2,\"cmd\":\"create_game\",\"game_id\":\"g1\",\"seed\":11,"
                    + "\"seats\":[{\"name\":\"You\",\"kind\":\"seat\",\"deck\":\"" + GameHostTest.BEARS + "\"},"
                    + "{\"name\":\"CPU\",\"kind\":\"cpu\",\"deck\":\"" + GameHostTest.BEARS + "\",\"skill\":6}]}");
            Assert.assertEquals("create_game: " + created, true, created.get("ok"));
            Assert.assertEquals(List.of("You"), created.get("seats"));

            ScriptedSeat script = new ScriptedSeat();
            boolean sawLandPlay = false;
            for (int i = 0; i < 40; i++) {
                Map<String, Object> d = call(out, in, "{\"id\":" + (10 + i) + ",\"cmd\":\"next_decision\",\"game_id\":\"g1\",\"seat\":\"You\",\"timeout_ms\":60000}");
                Assert.assertEquals("next_decision: " + d, true, d.get("ok"));
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertEquals(true, d.get("action_pending"));
                Map<String, Object> args = script.answer(d);
                if (script.lands > 0) {
                    sawLandPlay = true;
                }
                StringBuilder req = new StringBuilder("{\"id\":" + (100 + i) + ",\"cmd\":\"choose_action\",\"game_id\":\"g1\",\"seat\":\"You\"");
                for (Map.Entry<String, Object> e : args.entrySet()) {
                    req.append(",\"").append(e.getKey()).append("\":").append(GSON.toJson(e.getValue()));
                }
                req.append('}');
                Map<String, Object> answered = call(out, in, req.toString());
                Assert.assertEquals("choose_action: " + answered + " for " + d, true, answered.get("success"));
                if (sawLandPlay && script.seen.size() > 12) {
                    break;
                }
            }
            Assert.assertTrue("a land was played over the socket", sawLandPlay);
            Map<String, Object> state = call(out, in, "{\"id\":900,\"cmd\":\"state\",\"game_id\":\"g1\",\"seat\":\"You\"}");
            Assert.assertNotNull(state.get("board"));
            Map<String, Object> ended = call(out, in, "{\"id\":901,\"cmd\":\"end_game\",\"game_id\":\"g1\"}");
            Assert.assertEquals(true, ended.get("ended"));
            Map<String, Object> gone = call(out, in, "{\"id\":902,\"cmd\":\"state\",\"game_id\":\"g1\",\"seat\":\"You\"}");
            Assert.assertEquals(false, gone.get("ok"));
        } finally {
            host.close();
        }
    }

    private static Map<String, Object> call(PrintWriter out, BufferedReader in, String request) throws Exception {
        out.println(request);
        String line = in.readLine();
        Assert.assertNotNull("connection closed after: " + request, line);
        return GSON.fromJson(line, MAP);
    }
}
