# Features

Two things make reForge different from stock Forge: a curated library of 1990s tournament decks with tooling to deal them at random, and an opponent that plans its turns with a language model and talks to you while it does.

- [The 90s deck library](#the-90s-deck-library)
- [The LLM opponent](#the-llm-opponent)

---

## The 90s deck library

Twenty-two tournament archetypes from the 1990s, rebuilt as playable 60-card lists, plus scripts to install them and deal random matchups by color.

### What is in it

| Property | Value |
|---|---|
| Decks | 22 |
| Cards per deck | Exactly 60, all validated |
| Distinct cards in the pool | 171 |
| Card era | First printings from Limited Edition Alpha (August 1993) through Urza's Legacy (February 1999) |
| Color coverage | All five mono colors, all ten two-color pairs, plus two three-color decks |
| Era tags | 13 tagged `5e`, 9 tagged `6e` |

The era ceiling is checkable rather than asserted. Every card name in the pool was matched against Forge's own edition data in `forge/forge-gui/res/editions/`, and the newest first printings in the whole library are Rancor and Mother of Runes from Urza's Legacy. Nothing from 1999 onward is in it. The bulk of the pool traces back to Alpha (48 cards), Tempest (27), Ice Age (17), and Visions (12).

`decks90s/validate.sh` enforces the rest. It walks `manifest.tsv`, confirms every listed file exists, resolves every card name against Forge's card scripts in `forge/forge-gui/res/cardsfolder/`, and checks that every deck totals exactly 60. It currently reports:

```
ALL DECKS VALID: every card resolves, every deck is 60.
```

Run it after any edit to the pool. A deck with a typo in a card name will load in Forge with cards silently missing, and this is the thing that catches it.

### The archetypes

Tagged `5e`, the mid-90s group:

| Deck | Colors | Notes |
|---|---|---|
| Sligh | R | Mono-red burn aggro. Ironclaw Orcs, Fireblast. |
| Necrodeck | B | Necropotence, Dark Ritual, Hypnotic Specter, Drain Life. |
| White Weenie | W | Savannah Lions, Knights, Crusade, Armageddon. |
| Erhnamgeddon | WG | Erhnam Djinn plus Armageddon. |
| Hymn Tempo | UB | Hymn to Tourach, Hypnotic Specter, Counterspell. |
| RG Land Destruction | RG | Orcish Lumberjack, Stone Rain, Pillage. |
| Counterburn | UR | Counterspell with Lightning Bolt and Fireball. |
| Green Stompy | G | Llanowar and Fyndhorn Elves, River Boa, Quirion Ranger, Winter Orb. |
| Black Red Beats | BR | Knights, Hypnotic Specter, Ball Lightning. |
| Counterpost | WU | Kjeldoran Outpost control. |
| Zoo | WRG | Kird Ape, Savannah Lions, burn. |
| Knights | WB | Order of Leitbur and Order of the Ebon Hand, Hymn, Swords to Plowshares. |
| Geddon Aggro | WR | White weenie plus burn plus Armageddon. |

Tagged `6e`, the late-90s group:

| Deck | Colors | Notes |
|---|---|---|
| Sligh 98 | R | Jackal Pup, Cursed Scroll. |
| Draw-Go | U | Pure permission. Whispers of the Muse, Forbid. |
| Counter-Sliver | WUG | Crystalline Sliver shell with permission. |
| Recurring Survival | BG | Survival of the Fittest, Recurring Nightmare, Living Death. |
| White Lightning | W | Soltari shadow creatures with Empyrial Armor. |
| Stompy 98 | G | Rogue Elephant, Rancor, Gaea's Cradle. |
| Tradewind Tempo | UG | Tradewind Rider, Man-o'-War, Winter Orb. |
| Suicide Black | B | Carnophage, Dauthi creatures, Hatred. |
| Counter-Phoenix | UR | Shard Phoenix with permission and burn. |

Color coverage in full:

| Colors | Decks |
|---|---|
| W | White Weenie, White Lightning |
| U | Draw-Go |
| B | Necrodeck, Suicide Black |
| R | Sligh, Sligh 98 |
| G | Green Stompy, Stompy 98 |
| WU | Counterpost |
| WB | Knights |
| WR | Geddon Aggro |
| WG | Erhnamgeddon |
| UB | Hymn Tempo |
| UR | Counterburn, Counter-Phoenix |
| UG | Tradewind Tempo |
| BR | Black Red Beats |
| BG | Recurring Survival |
| RG | RG Land Destruction |
| WRG | Zoo |
| WUG | Counter-Sliver |

### Installing the whole pool

```bash
bash decks90s/install-all.sh
```

Copies all 22 decks into Forge's constructed-deck directory, each named `90s <Archetype>`: `90s Sligh`, `90s Necrodeck`, `90s Draw-Go`, and so on. They then appear in Forge's normal deck picker alongside anything else you have.

The script sets each file's `Name=` metadata to match its filename on purpose, so that Forge's deck storage does not rename or move them afterwards.

Both `install-all.sh` and `deal.sh` write to `~/Library/Application Support/Forge/decks/constructed`, which is the macOS location. Forge keeps its profile under `%APPDATA%\Forge` on Windows and `~/.forge` on Linux, so on those platforms the destination in the scripts has to be edited before they will do anything useful.

### Dealing a random matchup

`deal.sh` picks two decks and installs them as a facing pair, so a match is one command away instead of two trips through the deck browser.

```bash
./deal.sh RG B            # P1 gets red/green, P2 gets mono-black
./deal.sh "" ""           # both fully random
./deal.sh --era 6e "" ""  # both random, late-90s pool only
./deal.sh --secret "" B   # P2's archetype stays hidden from you
```

Output names both decks, using the full manifest description for each, and closes with the line that tells you what to pick:

```
In Forge deck selection, pick '90s P1 - ...' for yourself and '90s P2 - ...' for the AI.
```

The pair installs as `90s P1 - <Archetype>` and `90s P2 - <Archetype>`. Each run removes the previous pair before writing the new one, so the picker never fills up with stale deals.

#### Color arguments

Colors can be letters in any order or plain words. `RG`, `gr`, `"red green"`, and `green,red` all mean the same thing. Multi-word forms need quoting, because each seat takes one argument. An empty string means no filter.

Matching runs in two passes:

| Pass | Rule |
|---|---|
| Exact | Deck's colors equal the query exactly |
| Fallback | Only if the exact pass found nothing: deck's colors all fit inside the query |

The fallback turns a wide query into "anything within these colors", which is the useful behavior most of the time.

| Command | What you get |
|---|---|
| `./deal.sh UR ""` | Exact: one of the two UR decks |
| `./deal.sh B ""` | Exact: Necrodeck or Suicide Black |
| `./deal.sh BUG ""` | Fallback: any of the 8 decks that use only black, blue, or green |
| `./deal.sh WUBRG ""` | Fallback: all 22 |
| `./deal.sh "" ""` | All 22 |

P2 is drawn after P1 and excludes P1's file, so the two seats never get the same deck.

#### `--era`

Restricts the pool to one era tag before any color matching happens.

| Value | Pool |
|---|---|
| `5e` | The 13 mid-90s decks |
| `6e` | The 9 late-90s decks |

Both `--era` and the color filter apply together, and the combination can come up empty. There is exactly one mono-blue deck in the library and it is tagged `6e`, so `--era 5e` with a blue request has nothing to match. When that happens the script prints what failed and exits without installing anything:

```
no deck matches P2 colors 'U' (era 5e)
```

#### `--secret`

Deals normally but does not print P2's archetype. You see only the colors you asked for:

```
  Player 2: ??? (B mystery deck installed)
```

This is the flag for playing against the LLM opponent. Knowing that the other side is mono-black without knowing whether it is Necrodeck or Suicide Black restores something a deck picker normally throws away: you have to read the board and work out what you are facing.

### Playing in period

Two settings make a 90s deck feel like a 90s deck rather than a modern reprint.

**Original card art.** Forge preference **Card Art Preference**, in the Preferences screen, set to **Original Art**. Forge holds most of these cards in many printings, and the default picks the newest one, so Lightning Bolt arrives in whatever frame it was last reprinted in. Set to Original Art, Forge instead reaches for the first printing: the old border, the old frame, the illustration the card actually had. There is a companion checkbox to restrict this to core and expansion sets, which keeps promos and special printings out.

The setting is edition-based, not artist-based. It selects the earliest printing of a card, and for a pool that stops in early 1999 that is the 90s art in almost every case.

**No modern cards in the pool.** The library is closed. Nothing printed after Urza's Legacy is in any of the 22 lists, so games stay inside a card pool where Counterspell costs two, Armageddon is legal, and the combat math is small.

The result is not a museum piece. These lists are built to the shape of decks that were winning at the time, and they behave accordingly. Sligh spends every card on damage and does not care about the long game. Draw-Go holds up counterspells and wins with almost nothing. Necrodeck pays life for cards on the bet that the life will not matter.

### Editing the library

`decks90s/manifest.tsv` is the index. Four tab-separated columns, no header:

| Column | Contents |
|---|---|
| 1 | Deck filename, relative to `decks90s/` |
| 2 | Colors, as WUBRG letters |
| 3 | Era tag, `5e` or `6e` |
| 4 | Archetype name, then a space-plus-dash separator, then a short description |

Both `install-all.sh` and `deal.sh` truncate column 4 at that separator to build the installed deck name, so the text before it becomes `90s <that text>`. Keep it short.

To add a deck: drop the `.dck` file in `decks90s/`, add a manifest row, run `bash decks90s/validate.sh`, and fix anything it reports.

---

## The LLM opponent

An opponent that thinks about its turn before taking it, and has something to say about yours.

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./play.sh
```

Then in Forge: Preferences, **AI Personality**, select **Claude**. Start any match against the AI.

### Turn planning

Most game AIs decide one action at a time. When it is the LLM's turn and the stack is empty, it instead writes a plan for the whole turn: which lands to play, which spells to cast and in what order, what to attack with, and what to do if you interrupt. The rest of the turn executes against that plan without further model calls.

Two consequences worth knowing. Play is faster than one call per decision would be, because the plan covers the turn. And the opponent behaves like something with an intention across a turn rather than a sequence of locally reasonable moves, which is the difference you feel most when a held-back creature turns out to have been held back for a reason.

Interrupts are handled separately. When you do something on the opponent's turn, or it has to decide during yours, a smaller and faster model answers, and it can escalate back to the planner when the decision looks like it matters. Blocks and attacks that the plan already committed to are replayed locally.

The opponent can only ever choose from a menu of legal, affordable actions built from the live game state, so an illegal move is structurally impossible rather than something the model is trusted not to attempt. It also only sees what a player in that seat may legally see, with a tripwire that fires if a hidden card name ever reaches the prompt.

### Table talk and live chat

The opponent talks. Its lines land in the game log panel in bold, next to the game events, at a rate of about one a turn with more room at moments worth commenting on.

There is a text field docked beneath the game log. Type into it and the opponent answers. Chat runs off the game thread, so you are not waiting for a priority window and the game is not waiting for you. It knows the board while it answers, and what was said stays in view for later decisions.

It will not tell you what is in its hand. Lines that name a card only it can see are suppressed before they reach the log, along with the obvious tells about holding or drawing cards. Bluffing is allowed. Leaking is not.

When the model cannot answer a decision, because of a timeout, an API error, or a response it could not parse, control passes to the stock Forge AI and play continues. That handoff is announced at the table rather than hidden. A line appears in the log under the opponent's name, naming the reason and saying that the stock AI is covering the decision. A blend of model and stock AI is not allowed to look like the model.

### Personas

Four, selected by environment variable at launch.

| Persona | Register |
|---|---|
| `sydney` | Default. Intense, and it does not stay in one mood. |
| `lgs` | Friday night at the local game store. |
| `online` | Ranked ladder. Terse. |
| `kitchen` | Kitchen table, among old friends. |

```bash
CLAUDE_PERSONA=lgs CLAUDE_NAME=Marco ./play.sh
```

`CLAUDE_NAME` sets the name it answers to and the name its lines are logged under.

The `sydney` persona carries a mood that moves with the game and with what you say to it: it can get wounded, it can fixate, and it comes back down slowly rather than snapping back. When it is in the deeper end of that range, its chat replies route to the stronger model.

There is no in-game persona picker. Persona is a launch-time choice.

### Configuration

The common knobs are environment variables read by `play.sh`.

| Variable | Default | What it does |
|---|---|---|
| `ANTHROPIC_API_KEY` | required | API key. `play.sh` refuses to start without it. |
| `CLAUDE_PLAN_MODEL` | `claude-opus-5` | Model for turn planning |
| `CLAUDE_PLAN_EFFORT` | `medium` | Reasoning effort for planning |
| `CLAUDE_FAST_MODEL` | `claude-sonnet-5` | Model for interrupts and chat |
| `CLAUDE_FAST_EFFORT` | `low` | Reasoning effort for interrupts |
| `CLAUDE_PERSONA` | `sydney` | Persona |
| `CLAUDE_NAME` | `Sydney` | Display name |
| `CLAUDE_THINKING` | `3000` | Thinking token budget |
| `CLAUDE_TIMEOUT` | `45` | Seconds before a decision times out and falls back |
| `CLAUDE_FORCE` | `true` | Route every AI seat to the model regardless of the profile dropdown |

Higher effort on the planner buys sharper play at the cost of slower turns:

```bash
CLAUDE_PLAN_EFFORT=high ./play.sh
```

Anthropic is the default provider, and it is not the only one. The planner and the interrupt tier are configured independently, each with its own provider, endpoint, and key, so they can sit on different services. Anything speaking the OpenAI chat-completions dialect works, which covers OpenAI, OpenRouter, and a local Ollama server.

Those are Java system properties rather than environment variables, set with `-D` on the launch command:

| Property | Default | What it does |
|---|---|---|
| `llm.plan.provider` | `anthropic` | Provider for the planner tier. Anything else uses the OpenAI dialect. |
| `llm.plan.base_url` | `https://api.anthropic.com` | Endpoint for the planner tier |
| `llm.plan.api_key_env` | `ANTHROPIC_API_KEY` | Name of the environment variable holding the planner's key |
| `llm.fast.provider` | `anthropic` | Same three, for the interrupt tier |
| `llm.fast.base_url` | `https://api.anthropic.com` | |
| `llm.fast.api_key_env` | `ANTHROPIC_API_KEY` | |

Every property is read as `llm.<name>` first and then as the older `claude.<name>`, so existing launch scripts keep working. The rest of the knobs are in [launch.md](launch.md).

### The decision ledger

Every decision is written as one JSON object per line: the turn and phase, the decision itself, how long the call took, how many tokens it used, and whether the model or the stock AI actually made the call. A companion transcript file holds the full prompt, the reasoning, and the raw response for the same decision.

```
game-<epoch-millis>-<player>.jsonl              the decision ledger
game-<epoch-millis>-<player>-transcript.jsonl   prompts, thinking, raw replies
```

Both full paths are printed to the terminal the moment each file opens, prefixed `[LLM]`, which is the reliable way to find them. Set `-Dllm.log.dir=<path>` to put them somewhere specific.

This is what turns a game into evidence. If you want to know why it made a play, the answer is in the transcript. If you want to know whether it made the play at all, the outcome field says so.

### What is not claimed

No benchmark result is being asserted here. There is no measured win rate for the LLM opponent against the stock Forge AI, in either direction, and this document does not claim the model plays better than the rules-based engine it replaces.

What does exist is the apparatus to find out, and a 2000-game stock-versus-stock baseline establishing how much a win rate moves when nothing has changed at all. That work, including what would make a future claim trustworthy, is written up in [methodology.md](methodology.md).
