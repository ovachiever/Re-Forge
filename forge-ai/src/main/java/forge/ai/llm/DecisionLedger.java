package forge.ai.llm;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-game JSONL record of every strategic decision: who answered (Claude or a fallback),
 * why, at what latency and token cost. Win rates are meaningless without this beside them.
 */
public final class DecisionLedger {

    public enum Outcome {
        CLAUDE, MOCK, AUTO_PASS_CACHED,
        PLAN_STEP,   // executed locally from the turn plan — zero API
        FAST,        // fast-tier interrupt decision
        ESCALATED,   // fast tier punted to the planner
        FALLBACK_NO_KEY, FALLBACK_TIMEOUT, FALLBACK_PARSE, FALLBACK_API, FALLBACK_ERROR, FALLBACK_CAP
    }

    private final Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
    private long tokensIn, tokensOut, cacheRead, cacheWrite;
    private int records;
    private PrintWriter out;
    private PrintWriter transcriptOut;
    private final String playerName;
    private final long startTs = System.currentTimeMillis();

    public DecisionLedger(String playerName) {
        this.playerName = playerName;
    }

    private String baseName() {
        return "game-" + startTs + "-" + playerName.replaceAll("\\W+", "_");
    }

    private synchronized PrintWriter writer() {
        if (out == null) {
            out = openWriter(baseName() + ".jsonl", "decision ledger");
        }
        return out;
    }

    private synchronized PrintWriter transcriptWriter() {
        if (transcriptOut == null) {
            transcriptOut = openWriter(baseName() + "-transcript.jsonl", "reasoning transcript");
        }
        return transcriptOut;
    }

    private PrintWriter openWriter(String name, String what) {
        try {
            Path dir = Path.of(LlmConfig.logDir());
            Files.createDirectories(dir);
            Path f = dir.resolve(name);
            PrintWriter w = new PrintWriter(Files.newBufferedWriter(f, StandardCharsets.UTF_8));
            System.out.println("[LLM] " + what + ": " + f);
            return w;
        } catch (IOException e) {
            System.err.println("[LLM] " + what + " unavailable: " + e);
            return new PrintWriter(Writer.nullWriter());
        }
    }

    /** Full-fidelity record of one API decision: the exact prompt, the model's thinking, its raw reply. */
    public synchronized void transcript(int turn, String phase, String method,
                                        String prompt, String thinking, String raw) {
        JsonObject j = new JsonObject();
        j.addProperty("ts", System.currentTimeMillis());
        j.addProperty("turn", turn);
        j.addProperty("phase", phase);
        j.addProperty("method", method);
        j.addProperty("prompt", prompt);
        j.addProperty("thinking", thinking == null ? "" : thinking);
        j.addProperty("response", raw == null ? "" : raw);
        PrintWriter w = transcriptWriter();
        w.println(j);
        w.flush();
    }

    public synchronized void record(int turn, String phase, String method, Outcome outcome, long latencyMs,
                                    long inTok, long outTok, long cacheR, long cacheW, long thinkTok,
                                    String decision, String tableTalk) {
        counts.merge(outcome, 1, Integer::sum);
        tokensIn += inTok;
        tokensOut += outTok;
        cacheRead += cacheR;
        cacheWrite += cacheW;
        records++;

        JsonObject j = new JsonObject();
        j.addProperty("ts", System.currentTimeMillis());
        j.addProperty("turn", turn);
        j.addProperty("phase", phase);
        j.addProperty("method", method);
        j.addProperty("outcome", outcome.name());
        j.addProperty("latencyMs", latencyMs);
        j.addProperty("inTokens", inTok);
        j.addProperty("outTokens", outTok);
        j.addProperty("cacheRead", cacheR);
        j.addProperty("cacheWrite", cacheW);
        j.addProperty("thinkingTokens", thinkTok);
        j.addProperty("decision", decision);
        if (tableTalk != null) {
            j.addProperty("tableTalk", tableTalk);
        }
        PrintWriter w = writer();
        w.println(j);
        w.flush();

        System.out.println("[LLM] T" + turn + " " + phase + " " + method + " -> " + outcome
                + (latencyMs > 0 ? String.format(" (%.1fs)", latencyMs / 1000.0) : "") + " :: " + decision);
        if (records % 10 == 0) {
            System.out.println("[LLM] ledger so far: " + summary());
        }
    }

    public synchronized String summary() {
        int claude = counts.getOrDefault(Outcome.CLAUDE, 0) + counts.getOrDefault(Outcome.MOCK, 0)
                + counts.getOrDefault(Outcome.PLAN_STEP, 0) + counts.getOrDefault(Outcome.FAST, 0)
                + counts.getOrDefault(Outcome.ESCALATED, 0);
        int fallbacks = 0;
        for (Map.Entry<Outcome, Integer> e : counts.entrySet()) {
            if (e.getKey().name().startsWith("FALLBACK")) {
                fallbacks += e.getValue();
            }
        }
        return "claude=" + claude + " fallbacks=" + fallbacks + " detail=" + counts
                + " tokens=" + tokensIn + "in/" + tokensOut + "out cacheRead=" + cacheRead;
    }
}
