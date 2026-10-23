# EC Gwent

A Minecraft-themed reskin of Gwent from The Witcher 3, for the **Expanded Civilizations** server
(Forge 1.20.1). Players collect cards, build decks and challenge Easy NPCs and villagers to matches.

- Design notes (rules, factions, neutral cards): [Teemov/maindev docs/gwent-design.md](https://github.com/Teemov/maindev/blob/main/docs/gwent-design.md)
- Card list: [docs/cards.md](docs/cards.md)

This is a **client + server** mod: every player needs it installed.

## Unlocking

Gwent is unlocked per player by the storyline. Until `/gwent unlock <players>` has run for a player, the mod stays
out of their way: K does nothing, sneak + right-click on villagers works as in vanilla, and the `/gwent` commands are
hidden. `/gwent config requireUnlock false` turns this off so everyone can play.

## Playing

| What | How |
|---|---|
| Card book (collection, deck builder) | **K**, or `/gwent book`. The first time, you pick a starting faction and get its starter deck |
| Back to a running match | **K** |
| Challenge a player | Sneak + right-click them (or `/gwent duel <player> [bet]`). Each player picks their own bet (0-64 emeralds): the higher bet goes first, equal bets toss a coin, the winner takes both bets. Walking more than 32 blocks away or leaving counts as a loss |
| Challenge a villager | Sneak + right-click an adult villager. Costs 2 emeralds; higher-level villagers play stronger decks. The villager stands still and faces you while you play |
| Give up a match | Press Esc twice (the first press asks), the Forfeit button, or `/gwent forfeit` |
| Safety | While a match runs, you and your opponent (player, villager or NPC) take no damage, except from `/kill` and the void. It ends with the match |

On the board: click a hand card, then the highlighted row (or the card again). Agile units go on your close or ranged
row, horns and Junk Brew on one of your rows, a Decoy on one of your units. Right-click cancels. Click your leader to use it.

## Commands for operators (level 2)

| Command | Purpose |
|---|---|
| `/gwent unlock <players> [silent]` | Give players access to Gwent (with a short chat message unless `silent`). Put it in a quest reward |
| `/gwent lock <players>` | Take access away again (their cards are kept) |
| `/gwent config requireUnlock true\|false` | Whether players need to be unlocked first (default true) |
| `/gwent challenge <players> <opponent>` | Start a match against a story NPC. Put this on an Easy NPC dialog button: `/gwent challenge @initiator stuve` |
| `/gwent config villagerReward emeralds\|cards` | What beating a villager gives besides the returned bet: an extra emerald (default) or a random card from its deck |
| `/gwent give <players> <card>` | Give a card, e.g. `neutral:stuve` |
| `/gwent giveall <players>` | Every card at its full copy count (testing) |
| `/gwent reset <players>` | Wipe card collections (testing) |
| `/gwent version` | Version and how many cards and opponents are loaded |

## Data

- `data/ec_gwent/gwent_cards/<faction>.json`: the cards. Strength, row, abilities and copies follow Witcher 3.
- `data/ec_gwent/gwent_opponents/<id>.json`: story-NPC opponents:
  `{"name", "npc": "<Easy NPC UUID>", "faction", "quality": 0..1, "reward": "<card key>"}`.
  Instead of `quality` you can give a fixed deck with `"leader"` and `"cards": [...]`.
  The first win against an opponent gives the reward card; rematches give nothing.

Both can be overridden or extended by a data pack.

A card can use a hand-made face instead of the generated one: add `"image": "ec_gwent:textures/cards/<faction>/<id>.png"`
and `"image_size": [width, height]` to it (the Valarian troops use 144×200 pixel art). The printed strength is part of the
image; when the strength on the board differs, a green or red badge shows the current value over it.

## Match music

When a match starts, all other music (background music and jukeboxes) stops and a random match theme loops until the
match ends, then fades out; new music is held back meanwhile. It follows the Music volume slider. Themes are the
variants of the `ec_gwent:music.match` sound event in `assets/ec_gwent/sounds.json`: add another track by putting an
OGG Vorbis file in `assets/ec_gwent/sounds/music/` (lowercase name) and listing it there with `"stream": true`.
A resource pack can add or replace tracks the same way.

## Code

- `engine/`: the rules engine, AI and deck generation. Pure Java with no Minecraft code, tested by
  `src/test/java/.../EngineSelfTest.java` (scoring and ability checks plus 3000 AI-vs-AI matches).
- `server/`: card data loading, player collections (stored in the player's persisted data, so they survive death),
  matches, commands, per-world config.
- `client/`: card book, match board, villager prompt, card drawing with live mob models.
- `net/`: packets.

## Building

GitHub Actions builds every push and runs the engine self-test. On `main`, the jar and `build.log` are force-pushed
to the **`builds`** branch (`git fetch origin +builds:refs/remotes/origin/builds`).
Run the engine test locally without Minecraft:

```
javac --release 17 -d /tmp/eng src/main/java/io/github/teemov/ecgwent/engine/*.java src/test/java/io/github/teemov/ecgwent/engine/*.java
java -cp /tmp/eng io.github.teemov.ecgwent.engine.EngineSelfTest
```
