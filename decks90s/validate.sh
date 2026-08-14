#!/bin/bash
# Validate the 90s pool: every card name must resolve to a Forge card script,
# every deck must total exactly 60 cards, every manifest entry must have a file.
CARDS="$(cd "$(dirname "$0")/../forge/forge-gui/res/cardsfolder" && pwd)"
POOL="$(cd "$(dirname "$0")" && pwd)"
fail=0
norm() { echo "$1" | tr '[:upper:]' '[:lower:]' | sed "s/'//g; s/[^a-z0-9]/_/g; s/__*/_/g; s/^_//; s/_\$//"; }
while IFS=$'\t' read -r file colors era name; do
  [ -z "$file" ] && continue
  path="$POOL/$file"
  if [ ! -f "$path" ]; then echo "MISSING FILE: $file"; fail=1; continue; fi
  total=0
  while read -r line; do
    case "$line" in ''|'['*) continue ;; Name=*) continue ;; esac
    count="${line%% *}"; cardname="${line#* }"
    case "$count" in ''|*[!0-9]*) continue ;; esac
    total=$((total + count))
    n=$(norm "$cardname")
    first="${n:0:1}"
    if [ ! -f "$CARDS/$first/$n.txt" ]; then
      echo "BAD CARD in $file: '$cardname' (no $first/$n.txt)"
      fail=1
    fi
  done < "$path"
  if [ "$total" -ne 60 ]; then echo "BAD COUNT in $file: $total cards (want 60)"; fail=1; fi
done < "$POOL/manifest.tsv"
[ $fail -eq 0 ] && echo "ALL DECKS VALID: every card resolves, every deck is 60."
exit $fail
