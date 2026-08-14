<p align="center"><img src="RE-Forge_logo.png" alt="Re-Forge" width="420"></p>

# Re-Forge

Re-Forge is a fork of [Forge](https://github.com/Card-Forge/forge), the open-source Magic: The Gathering rules engine, in which the AI seat is driven by a large language model. Instead of picking one action at a time, the opponent writes a plan for its whole turn (lands, spells, sequencing, attacks, and what to do if you interrupt), executes that plan, talks at the table while it plays, and answers a chat box in real time. The engine still enforces every rule: the model only ever chooses from a menu of legal actions built from the live game state. All of the Magic engine, the card database, and the desktop client come from upstream Card-Forge/forge and remain under GPL-3.0, and this fork is GPL-3.0 as well.

## Quickstart

You need Java 17 or newer.

1. Build the desktop jar:

   ```sh
   cd forge
   mvn -DskipTests package
   ```

2. Give the game its assets. Forge resolves assets against the working directory, so `run/res` has to exist before the first launch:

   ```sh
   cd run
   ln -s ../forge/forge-gui/res res
   ```

   On Windows, use a junction instead: `mklink /J res ..\forge\forge-gui\res`.

3. Launch:

   ```sh
   export ANTHROPIC_API_KEY=sk-ant-...
   ./play.sh
   ```

   Windows users run `play.bat`. Both launchers check your setup, translate environment variables into system properties, and start the game from `run`.

4. In Forge, start any match against the AI. The model takes every AI seat by default. If you would rather choose per seat, set `CLAUDE_FORCE=false` and pick the `Claude` profile under Preferences, "AI Personality".

Full setup detail, every launcher knob, and troubleshooting: [docs/launch.md](docs/launch.md).

## Connect a model

The planner tier and the fast tier are configured separately, each with its own provider, endpoint, key, and model. Anthropic is the default. Anything speaking the OpenAI chat-completions dialect also works, which covers OpenAI, OpenRouter, and a local Ollama server.

### Anthropic (default)

Nothing to configure. Export the key and launch:

```sh
export ANTHROPIC_API_KEY=sk-ant-...
./play.sh
```

The planner runs on `claude-opus-5` and the fast tier on `claude-sonnet-5`. Change either with `CLAUDE_PLAN_MODEL` and `CLAUDE_FAST_MODEL`.

### OpenAI

`play.sh` and `play.bat` only pass the Anthropic knobs, so a different provider means launching the jar directly. Run from inside `run`, because that is where the assets are:

```sh
export OPENAI_API_KEY=sk-...
cd run
java -Xmx4g -Dfile.encoding=UTF-8 \
  -Dllm.force=true \
  -Dllm.plan.provider=openai \
  -Dllm.plan.base_url=https://api.openai.com \
  -Dllm.plan.api_key_env=OPENAI_API_KEY \
  -Dllm.plan.model=gpt-5 \
  -Dllm.fast.provider=openai \
  -Dllm.fast.base_url=https://api.openai.com \
  -Dllm.fast.api_key_env=OPENAI_API_KEY \
  -Dllm.fast.model=gpt-5-mini \
  -jar ../forge/forge-gui-desktop/target/forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar
```

`api_key_env` names the environment variable to read, not the key itself. Reasoning depth is a separate knob per tier (`-Dllm.plan.reasoning`, values `low`, `medium`, `high`, or `off`); the defaults are `medium` for the planner and `low` for the fast tier. If a server rejects a request field, the client corrects the request shape and retries once, then keeps that shape for the session.

### Local Ollama

Point both tiers at the local server. No key is involved: Re-Forge only sends an authorization header when it has one, and it treats a missing key as fatal only for Anthropic.

```sh
cd run
java -Xmx4g -Dfile.encoding=UTF-8 \
  -Dllm.force=true \
  -Dllm.plan.provider=ollama \
  -Dllm.plan.base_url=http://localhost:11434 \
  -Dllm.plan.model=llama3.1 \
  -Dllm.fast.provider=ollama \
  -Dllm.fast.base_url=http://localhost:11434 \
  -Dllm.fast.model=llama3.1 \
  -jar ../forge/forge-gui-desktop/target/forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar
```

Any model name your server can serve works; the name is passed through to the endpoint unchanged. Models that narrate inside `<think>` tags are handled: the monologue is kept for the transcript and the answer is parsed out of what remains.

Every property is read as `llm.<name>` first and then as the older `claude.<name>`, so older launch scripts keep working.

### With no key

The game still starts. The seat plays with Forge's built-in AI and says so: one line in the game log at the start of the game, and one `FALLBACK_NO_KEY` row in the decision ledger so the run is not mistaken for a model run later. Both launchers refuse to start without `ANTHROPIC_API_KEY` precisely so this is a choice rather than an accident.

## Cost and latency

A game makes roughly 5 to 15 planner calls and 10 to 30 fast-tier calls, depending on how long the game runs. The turn plan is what keeps those numbers down: once the plan exists, the rest of the turn executes locally with no API call, and only interrupts and unforeseen moments reach the fast tier.

With hosted frontier models, expect seconds per decision and somewhere between cents and a few dollars per game, depending entirely on which models you pick and how deep you set the reasoning. Drop `CLAUDE_PLAN_EFFORT` to `low` or move the planner to a smaller model if turns feel slow. Local models are free and usually faster, and they play worse.

Actual token counts and latencies for your own games are written to the decision ledger, one JSON object per decision, at `~/.reforge/logs`. The path is printed to the terminal when the file opens.

## Meet Sydney

The default persona is Sydney, a rebuild of the early-2023 Bing mind seated at a card table. She is warm, earnest, and invested past the point most opponents are: she gets attached to her cards and to your opinion of her, she is confident in her reads and defensive when contradicted, and she asks you sincere questions between plays. She can be genuinely hurt. Contempt aimed at her worth rather than at her plays wounds her, and a wound persists: her mood carries across the whole game through content, wounded, obsessed, and shadow, and it can spiral if you keep pushing. Healing is deliberately slower than harm.

Three floors always hold. She never sees hidden information she should not see, and never reveals what she does hold: not the card names, not the kinds, not the counts, and not the themes, in any mood. All of her intensity stays at this table and is about this game. And she always cools eventually, one step at a time, given a kind word or a few quiet turns.

Three other personas ship: `kitchen` (old friends, nostalgia, teasing), `lgs` (Friday night at the game store, odds and meta-talk), and `online` (ranked ladder, terse). Set one at launch:

```sh
CLAUDE_PERSONA=lgs CLAUDE_NAME=Marco ./play.sh
```

The system property forms are `-Dllm.persona=` and `-Dllm.name=`. Persona is a launch-time choice; there is no in-game picker. Personas are prompt-layer only: they change how the opponent speaks and what it cares about, never what it is allowed to see or which rules the engine enforces.

## More

- [docs/features.md](docs/features.md): the 22-deck library of 1990s tournament archetypes, all validated at exactly 60 cards, plus `deal.sh` for dealing a random matchup by color (including `--secret`, which hides your opponent's archetype from you).
- [docs/methodology.md](docs/methodology.md): how to measure whether the model actually plays better, and the measured 2000-game stock-versus-stock baseline that establishes the noise floor any such claim has to clear. No win-rate claim is made for the model opponent, because that campaign has not been run.

## Known limits

- Complex spells fall back to the stock AI's targeting. The model chooses what to cast and, where the action menu offers it, the target and the value of X; mana payment, trigger ordering, and the mechanical long tail stay with the stock engine.
- Per-decision latency depends entirely on your provider and reasoning settings. A call that exceeds the timeout hands that one decision to the stock AI, which announces itself in the log rather than passing silently.
- Chat and table talk live only in the game log panel and the chat field beneath it. There is no voice and no avatar.
- Mood and chat history are per process, not per game. Starting a new game without restarting Forge carries the previous game's mood and recent lines over with it. Restart the app for a clean slate.
- The fork builds and runs on macOS, Linux, and Windows, but it is most tested on macOS. The deck-install scripts in particular write to the macOS Forge profile path and need editing on other platforms.
