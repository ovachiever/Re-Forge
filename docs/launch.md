# Launching reForge

reForge is a Forge fork whose AI seat is driven by a language model.
Two launchers ship with it. `play.sh` covers macOS and Linux, `play.bat`
covers Windows. They do the same three things: check your setup, set the
model configuration from environment variables, and start the game from
the `run` directory.

## What you need

1. Java 17 or newer. The project compiles to release 17, and the game
   refuses to start on anything older.
2. The desktop jar, built at
   `forge/forge-gui-desktop/target/forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar`.
3. A `res` directory inside `run`. See the next section.
4. An Anthropic API key in `ANTHROPIC_API_KEY`. Both launchers stop with a
   message if it is missing.

## The run directory

Forge resolves its assets against the process working directory. For the
desktop build the assets root is empty, so the game looks for `res` right
where it was started. Both launchers change into `run` for you, which is
why `run/res` has to exist first.

The layout the launchers expect:

```
reForge/
  play.sh
  play.bat
  docs/launch.md
  run/
    res            link or copy of forge/forge-gui/res
  forge/
    forge-gui/res/
    forge-gui-desktop/target/forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar
```

Both launchers verify `run/res/cardsfolder` rather than `run/res` itself,
so an empty directory made by hand fails the check instead of producing a
confusing crash later.

`forge/forge-gui/res` is roughly 456 MB. A link costs nothing. A copy costs
the full 456 MB and goes stale whenever the resources change, so prefer a
link on every platform that allows one.

### Creating res on macOS and Linux

```sh
cd /path/to/reForge/run
ln -s ../forge/forge-gui/res res
```

The link target is relative, so the checkout stays movable.

### Creating res on Windows

A directory junction is the best option. It needs no administrator rights
and no Developer Mode:

```bat
cd C:\path\to\reForge\run
mklink /J res ..\forge\forge-gui\res
```

A directory symlink also works, but it requires an elevated prompt or
Developer Mode turned on:

```bat
mklink /D res ..\forge\forge-gui\res
```

A plain copy always works and needs no privileges at all:

```bat
xcopy /E /I ..\forge\forge-gui\res res
```

If you use a copy, repeat it after any change to `forge/forge-gui/res`.

### Building the jar

From the `forge` directory:

```sh
mvn -DskipTests package
```

The assembly plugin is bound to the package phase in the desktop module,
so this produces the jar with dependencies. To build only what the desktop
jar needs:

```sh
mvn -pl forge-gui-desktop -am -DskipTests package
```

`forge/CONTRIBUTING.md` mentions a `windows-linux` profile. That profile
does not exist in this fork's pom, so leave the flag off.

## Launching

### macOS and Linux

```sh
export ANTHROPIC_API_KEY=sk-ant-...
./play.sh
```

`play.sh` adds `-Dapple.awt.application.name=reForge` only on macOS. The
guard reads `OSTYPE` and falls back to `uname -s`, so Linux and any shell
that leaves `OSTYPE` unset both behave correctly.

### Windows

```bat
set ANTHROPIC_API_KEY=sk-ant-...
play.bat
```

`set` lasts for the current console only. To keep the key across consoles:

```bat
setx ANTHROPIC_API_KEY sk-ant-...
```

A `setx` value reaches only consoles opened after you run it, so open a new
one before launching.

`play.bat` checks the key first, then that Java is on `PATH` and that its
major version is 17 or newer, then the res directory and the jar. It passes
no `apple.awt.*` flags.

### Taking a seat

In Forge, open Preferences with the gear icon, find "AI Personality", and
select Claude. You can skip that step: `CLAUDE_FORCE` defaults to `true`,
which routes every AI seat to the model regardless of the per-seat profile
dropdown in the lobby. Set `CLAUDE_FORCE=false` if you want the lobby
dropdown to decide.

Table talk shows up in the game log panel during play.

## Environment knobs

Every knob below is read by both launchers and turned into a `-Dclaude.*`
system property. Defaults are the launcher defaults.

| Variable | Default | Property | Effect |
| --- | --- | --- | --- |
| `ANTHROPIC_API_KEY` | none, required | read from the environment | The API key. Both launchers refuse to start without it. |
| `CLAUDE_FORCE` | `true` | `claude.force` | Routes every AI seat to the model, ignoring the lobby's per-seat profile dropdown. |
| `CLAUDE_PLAN_MODEL` | `claude-opus-5` | `claude.model.plan` | Planner tier. Lays out the whole turn, declares blocks, takes escalations. |
| `CLAUDE_PLAN_EFFORT` | `medium` | `claude.effort.plan` | Reasoning depth for the planner: `low`, `medium`, or `high`. |
| `CLAUDE_FAST_MODEL` | `claude-sonnet-5` | `claude.model.fast` | Fast tier. Triages unforeseen interrupts and may escalate to the planner. |
| `CLAUDE_FAST_EFFORT` | `low` | `claude.effort.fast` | Reasoning depth for the fast tier. |
| `CLAUDE_PERSONA` | `sydney` | `claude.persona` | Table persona: `sydney`, `kitchen`, `lgs`, or `online`. Anything else falls back to `sydney`. |
| `CLAUDE_NAME` | `Sydney` | `claude.name` | The name used in the game log and on signed table talk. |
| `CLAUDE_THINKING` | `3000` | `claude.thinking.budget` | Legacy extended-thinking token budget. Only pre-Claude-5 models use it. Values from 1 to 1023 are raised to 1024. |
| `CLAUDE_TIMEOUT` | `45` | `claude.timeout.seconds` | Per-call API timeout. The code's own default is 90, so the launchers are stricter than a bare jar run. |

Heap size is fixed at `-Xmx4g` in both launchers and has no environment
knob. Edit the launcher if you need a different figure.

The `claude.*` property names are the long-lived ones. A parallel change
adds `llm.*` aliases that fall back to these, so the names above stay
valid.

## Properties without an environment knob

These are read by the game but not wired to a variable. Add them to the
java line in the launcher if you want them.

| Property | Default | Effect |
| --- | --- | --- |
| `claude.api.key` | unset | Key source that wins over `ANTHROPIC_API_KEY`. |
| `claude.log.dir` | see below | Where the ledger and transcript are written. |
| `claude.mock` | `false` | Runs the full pipeline with no network calls. |
| `claude.strict` | `false` | Hard-fails instead of quietly blending in stock AI decisions. |
| `claude.leakcheck` | `log` | `hard` throws on a hidden-zone leak. `log` complains and keeps playing. |
| `claude.model` | `claude-opus-5` | Planner model when `claude.model.plan` is unset. |
| `claude.effort` | `high` | Reasoning depth outside the planner and fast split. |
| `claude.thinking.mode` | `auto` | Force `adaptive`, `budget`, or `off` instead of picking by model family. |
| `claude.max.tokens` | `1200` | Ceiling on answer text. |
| `claude.max.tokens.total` | `6000` | Total output ceiling in adaptive mode, where thinking shares the budget. |
| `claude.maxcalls.per.turn` | `25` | Cap on API calls in a single turn. |
| `claude.prompt.file` | unset | File that replaces the embedded system prompt. Editable between games. |

## Where logs land

Both launchers run java in the foreground, so everything the game prints
goes to the terminal or console you launched from. Lines from the model
seat are prefixed `[Claude]`.

Two JSONL files are written per game:

```
game-<epoch-millis>-<player>.jsonl              the decision ledger
game-<epoch-millis>-<player>-transcript.jsonl   prompts, thinking, raw replies
```

Their full paths are printed to standard output the moment each file opens,
which is the reliable way to find them.

The default directory is `~/.reforge/logs` on every platform (home directory
plus `.reforge/logs`). Override it with `-Dllm.log.dir=...`; the legacy
`-Dclaude.log.dir=...` is still honored as a fallback. Games played before
this default changed logged to `~/Library/Application Support/Forge/claude-logs`
on macOS; those files stay where they are.

Forge's own profile data does resolve per platform:

| Platform | Data | Cache |
| --- | --- | --- |
| macOS | `~/Library/Application Support/Forge` | `~/Library/Caches/Forge` |
| Windows | `%APPDATA%\Forge` | `%LOCALAPPDATA%\Forge\Cache` |
| Linux and others | `~/.forge` | `~/.cache/forge` |

Preferences, decks, and saved games live there.

## Troubleshooting

**The launcher says `ANTHROPIC_API_KEY is not set`.**
Export it, or set it with `set` on Windows, then launch again. Using a
non-Anthropic provider instead? Set `LLM_OPTS` with your `-Dllm.*` flags and
the launcher passes them through without requiring an Anthropic key. With no
key at all the game still starts: the seat plays with the stock AI, announces
that once in the game log, and prints a console warning starting with
`[LLM] no API key`.

**The launcher says `missing or incomplete run/res`.**
Create the link or copy described above. This check looks for
`run/res/cardsfolder`, so an empty `run/res` fails it too.

**The launcher says the jar is missing.**
Build it with Maven from the `forge` directory.

**Java is too old, or `java` is not found.**
Forge needs Java 17 or newer. Check with `java -version`. `play.bat` refuses
to continue below 17. On macOS and Linux the game itself will fail on an old
runtime.

**The first launch takes a long time.**
Forge reads the entire card database out of `res/cardsfolder` at startup.
Tens of thousands of small files, so the first run is slow and the cold-cache
case is slower still. Later launches are faster because the operating system
has the files cached. A copied `res` behaves the same as a linked one here.

**Card text or table talk shows garbled characters on Windows.**
Java 17 uses the platform charset by default. Java 18 and newer default to
UTF-8. The stock Forge launchers pass `-Dfile.encoding=UTF-8` for this
reason. If you hit it, add `-Dfile.encoding=UTF-8` to the java line in
`play.bat`.

**Turns feel slow.**
Drop `CLAUDE_PLAN_EFFORT` to `low`, or point `CLAUDE_PLAN_MODEL` at the
faster model. `CLAUDE_TIMEOUT` caps how long any single call may run before
the stock AI covers the decision.

**The AI does not seem to be the model.**
Check that `CLAUDE_FORCE` is still `true`, or set the AI Personality
preference to Claude. Any decision that falls back announces itself at the
table, so a silent blend is not possible.
