#!/usr/bin/env bash
# Rifà da capo, in fila, i passi di foto-da-pcloud.sh che controllano che sia stato
# preso tutto: ognuno rifà solo quello che manca. Non archivia e non cancella niente.
#
#   verifica-tutto.sh          parte in sottofondo (si può chiudere il terminale)
#   verifica-tutto.sh dove     a che passo è arrivato, e le ultime righe
#   verifica-tutto.sh da N     riparte dal passo N (per esempio dopo un errore)
#
# I passi:
#   1 stato      a che punto è il lavoro
#   2 scarica    pCloud -> copia sul PC: prende solo quello che manca
#   3 raccogli   le foto e gli zip non ancora in ordinate/ (deve dire "0 ... da fare")
#   4 carica     ordinate/ -> LifetimeCloud e controllo (si ferma se trova cartelle doppie)
#   5 controllo  rclone check ordinate/ contro LifetimeCloud: deve dare 0 mancanti
#   6 conti      quante foto in ordinate/, e cosa è rimasto apposta su pCloud
#   7 libera     cancella la copia sul PC (LAVORO/specchio), solo se il passo 5 di questo
#                giro è andato bene. Gli originali restano su pCloud, ordinate/ resta.
#                Dopo, un nuovo "scarica" riscaricherebbe tutto: per questo è l'ultimo.
# Al primo errore si ferma e dice da quale passo ripartire. (Dentro "if ! passo" set -e
# non vale: ogni comando del passo ha il suo "|| return 1".)
#
# Il registro è LAVORO/verifica.log, il passo a cui è arrivato LAVORO/verifica.passo.
# Le impostazioni (LAVORO, RCLONE, CARICA_PARALLELI, ...) sono quelle di foto-da-pcloud.sh.
set -euo pipefail

QUI="$(cd "$(dirname "$0")" && pwd)"
FOTO="${FOTO:-$QUI/foto-da-pcloud.sh}"
LAVORO="${LAVORO:-$HOME/foto-da-pcloud}"
RCLONE="${RCLONE:-rclone}"
REGISTRO="$LAVORO/verifica.log"
PASSO="$LAVORO/verifica.passo"
NOMI=(stato scarica raccogli carica controllo conti libera)
CONTROLLATO="$LAVORO/verifica-controllo.ok"
TOTALE=${#NOMI[@]}

errore() { echo "Errore: $*" >&2; exit 1; }
ora() { date '+%d/%m %H:%M'; }

dove() {
    if [ ! -f "$PASSO" ]; then
        echo "Non è ancora partito: $0"
        return
    fi
    cat "$PASSO"
    if pgrep -f '^[^ ]*bash [^ ]*verifica-tutto\.sh esegui' >/dev/null; then
        echo "(sta girando)"
    fi
    echo "--- ultime righe di $REGISTRO"
    tail -n 8 "$REGISTRO" 2>/dev/null || true
}

# Valore di un'impostazione di foto-da-pcloud.sh (DESTINAZIONE), se c'è.
impostazione() {
    local v=""
    [ -f "$LAVORO/impostazioni" ] && v="$(sed -n "s/^$1=//p" "$LAVORO/impostazioni" | tail -1)"
    [ -n "$v" ] && eval "printf '%s' $v"
}

passo() {   # passo N: esegue il passo N
    local n="$1" nome="${NOMI[$(($1 - 1))]}"
    echo "[$n/$TOTALE] $nome: iniziato $(ora)" > "$PASSO"
    echo
    echo "===== [$n/$TOTALE] $nome ($(ora)) ====="
    case "$nome" in
        stato|scarica|raccogli|carica) "$FOTO" "$nome" || return 1 ;;
        controllo)
            local dest
            dest="${DESTINAZIONE:-$(impostazione DESTINAZIONE)}"
            dest="${dest:-lifetime:FotoTimeline}"
            "$RCLONE" check "$LAVORO/ordinate" "$dest" --one-way --size-only \
                2> >(grep -Ev ': (File not in|sizes differ)' >&2) || return 1
            echo "Su $dest c'è tutto quello che c'è in $LAVORO/ordinate."
            date +%s > "$CONTROLLATO"
            ;;
        conti)
            echo "Foto e video in ordinate/: $(find "$LAVORO/ordinate" -type f | wc -l)"
            echo "  (nell'app, a indicizzazione finita, il totale deve essere almeno questo)"
            if [ -f "$LAVORO/da-controllare.txt" ]; then
                echo "Rimasti su pCloud apposta (elencati in $LAVORO/da-controllare.txt):"
                echo "  zip con anche altri file: $(grep -c '^zip con' "$LAVORO/da-controllare.txt" || true)"
                echo "  file non letti:           $(grep -c '^non letto' "$LAVORO/da-controllare.txt" || true)"
            fi
            ;;
        libera)
            [ -f "$CONTROLLATO" ] || { echo "Il controllo (passo 5) non è passato in questo giro: non cancello niente."; return 1; }
            if [ -d "$LAVORO/specchio" ]; then
                echo "Spazio libero prima: $(df -h --output=avail "$LAVORO" | tail -1 | tr -d ' ')"
                echo "Cancello $LAVORO/specchio ($(du -sh "$LAVORO/specchio" | cut -f1); lo spazio delle foto uguali a quelle in ordinate/ non si libera: sono gli stessi dati)…"
                rm -rf "$LAVORO/specchio" || return 1
                echo "Spazio libero dopo:  $(df -h --output=avail "$LAVORO" | tail -1 | tr -d ' ')"
            else
                echo "$LAVORO/specchio non c'è già più."
            fi
            ;;
    esac
    echo "[$n/$TOTALE] $nome: finito $(ora)" > "$PASSO"
}

esegui() {   # esegui DA: i passi da DA alla fine, nel registro
    local da="$1" n
    mkdir -p "$LAVORO"
    # Il permesso di cancellare specchio/ vale solo per un controllo fatto in questo giro.
    if [ "$da" -le 5 ]; then rm -f "$CONTROLLATO"; fi
    for ((n = da; n <= TOTALE; n++)); do
        if ! passo "$n"; then
            echo "[$n/$TOTALE] ${NOMI[$((n - 1))]}: ERRORE $(ora). Guarda $REGISTRO, poi: $0 da $n" > "$PASSO"
            echo
            echo "Fermo al passo $n (${NOMI[$((n - 1))]}). Sistemato il problema: $0 da $n"
            exit 1
        fi
    done
    echo "FATTO $(ora): tutti i $TOTALE passi senza errori. Ora nell'app: Indicizza archivio." > "$PASSO"
    echo
    cat "$PASSO"
}

avvia() {   # avvia DA: in sottofondo, staccato dal terminale
    local da="$1"
    [[ "$da" =~ ^[0-9]+$ ]] && [ "$da" -ge 1 ] && [ "$da" -le "$TOTALE" ] || errore "passo da 1 a $TOTALE"
    [ -x "$FOTO" ] || errore "non trovo $FOTO"
    if pgrep -f '^[^ ]*bash [^ ]*verifica-tutto\.sh esegui' >/dev/null || pgrep -f '^[^ ]*bash [^ ]*foto-da-pcloud\.sh (scarica|raccogli|carica)' >/dev/null; then
        errore "c'è già un giro in corso: $0 dove"
    fi
    mkdir -p "$LAVORO"
    : > "$REGISTRO"
    setsid nohup "$0" esegui "$da" >> "$REGISTRO" 2>&1 < /dev/null &
    echo "Partito dal passo $da di $TOTALE, in sottofondo."
    echo "Per vedere a che punto è:  $0 dove"
    echo "Per seguirlo:              tail -f $REGISTRO"
}

case "${1:-}" in
    "") avvia 1 ;;
    da) avvia "${2:-}" ;;
    dove) dove ;;
    esegui) esegui "${2:-1}" ;;
    *) errore "uso: $0 [dove | da N]" ;;
esac
