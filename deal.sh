#!/bin/bash
# Deal random 90s-era constructed decks by color.
#   ./deal.sh RG B          — P1 gets a red/green deck, P2 gets a mono-black deck
#   ./deal.sh "" ""         — both fully random
#   ./deal.sh --era 5e W B  — restrict pool to pre-5th-Edition-era decks
#   ./deal.sh --secret R B  — hide P2's archetype (mystery opponent for playing vs Claude)
# Colors: letters WUBRG in any order, or words (red, green...). Empty/any = no filter.
# Decks install into Forge as "90s P1 - <Name>" / "90s P2 - <Name>" (old ones replaced).
set -euo pipefail
POOL="$(cd "$(dirname "$0")" && pwd)/decks90s"
DEST="$HOME/Library/Application Support/Forge/decks/constructed"
ERA=""; SECRET=0; ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --era) ERA="$2"; shift 2 ;;
    --secret) SECRET=1; shift ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
Q1="${ARGS[0]:-}"; Q2="${ARGS[1]:-}"

canon() { # words/letters -> canonical WUBRG-ordered string; empty stays empty
  local s; s=$(echo "$1" | tr '[:lower:]' '[:upper:]')
  s=$(echo "$s" | sed 's/WHITE/W/g; s/BLUE/U/g; s/BLACK/B/g; s/RED/R/g; s/GREEN/G/g; s/[^WUBRG]//g')
  local out=""
  for c in W U B R G; do case "$s" in *"$c"*) out="$out$c";; esac; done
  echo "$out"
}

pick() { # $1=colors query  $2=exclude-file  -> "file|name" of a random matching deck
  local q; q=$(canon "$1")
  local matches=()
  while IFS=$'\t' read -r file colors era name; do
    [ -z "$file" ] && continue
    [ -n "$ERA" ] && [ "$era" != "$ERA" ] && continue
    [ "$file" = "${2:-}" ] && continue
    if [ -z "$q" ] || [ "$(canon "$colors")" = "$q" ]; then matches+=("$file|$name"); fi
  done < "$POOL/manifest.tsv"
  if [ ${#matches[@]} -eq 0 ] && [ -n "$q" ]; then
    # no exact color match: fall back to decks whose colors fit inside the query
    while IFS=$'\t' read -r file colors era name; do
      [ -z "$file" ] && continue
      [ -n "$ERA" ] && [ "$era" != "$ERA" ] && continue
      [ "$file" = "${2:-}" ] && continue
      local c q_ok=1; local cc; cc=$(canon "$colors")
      for (( i=0; i<${#cc}; i++ )); do c="${cc:$i:1}"; case "$q" in *"$c"*) ;; *) q_ok=0;; esac; done
      [ $q_ok -eq 1 ] && matches+=("$file|$name")
    done < "$POOL/manifest.tsv"
  fi
  [ ${#matches[@]} -eq 0 ] && { echo ""; return; }
  echo "${matches[$(( RANDOM % ${#matches[@]} ))]}"
}

install() { # $1=file $2=slot(P1/P2) $3=name -> installs as "90s <slot> - <name>"
  local target="90s $2 - $3"
  rm -f "$DEST/90s $2 - "*.dck
  sed "s/^Name=.*/Name=$target/" "$POOL/$1" > "$DEST/$target.dck"
}

P1=$(pick "$Q1" ""); [ -z "$P1" ] && { echo "no deck matches P1 colors '$Q1'${ERA:+ (era $ERA)}"; exit 1; }
P1F="${P1%%|*}"; P1N="${P1##*|}"
P2=$(pick "$Q2" "$P1F"); [ -z "$P2" ] && { echo "no deck matches P2 colors '$Q2'${ERA:+ (era $ERA)}"; exit 1; }
P2F="${P2%%|*}"; P2N="${P2##*|}"

install "$P1F" P1 "${P1N%% —*}"
install "$P2F" P2 "${P2N%% —*}"

echo "Dealt:"
echo "  Player 1: ${P1N}"
if [ $SECRET -eq 1 ]; then
  echo "  Player 2: ??? ($(canon "$Q2" | sed 's/./& /g')mystery deck installed)"
else
  echo "  Player 2: ${P2N}"
fi
echo
echo "In Forge deck selection, pick '90s P1 - ...' for yourself and '90s P2 - ...' for the AI."
