#!/usr/bin/env bash
# Porta le foto e i video di pCloud in FotoTimeline e li riordina anche su pCloud.
# Gira sul PC (WSL), con pCloud Drive su P: (sudo mount -t drvfs P: /mnt/p) e rclone
# con il remote "lifetime:" (WebDAV di LifetimeCloud).
#
#   foto-da-pcloud.sh anteprima  senza copiare niente: quante foto, video e zip ci sono
#                                in ogni cartella di RADICE, per scegliere cosa ESCLUDERE
#   foto-da-pcloud.sh scarica    con rclone (remote PCLOUD, di default "pcloud:"), in parallelo:
#                                foto, video e zip di RADICE in LAVORO/specchio, saltando gli
#                                esclusi. Molto più veloce che leggere da P: (consigliato)
#   foto-da-pcloud.sh raccogli   cerca foto e video (da specchio/ se c'è, se no da RADICE),
#                                anche dentro gli zip, e li mette in LAVORO/ordinate/AAAA/MM/GG
#   foto-da-pcloud.sh carica     ordinate/ -> lifetime:FotoTimeline, poi controlla
#   foto-da-pcloud.sh archivia   ordinate/ -> ARCHIVIO/AAAA/AAAA-MM.zip su pCloud, poi controlla
#   foto-da-pcloud.sh pulisci    dopo GIORNI giorni da carica e archivia: cancella da pCloud
#                                gli originali copiati (file, zip, cartelle rimaste vuote)
#   foto-da-pcloud.sh stato      a che punto è
#
# La prima volta (poi si ricordano in LAVORO/impostazioni):
#   RADICE='/mnt/p' ARCHIVIO='/mnt/p/Archivio foto' foto-da-pcloud.sh anteprima
#   ESCLUDI='TASSE:banche:avvocato' foto-da-pcloud.sh anteprima     # finché torna
#   foto-da-pcloud.sh scarica
#   foto-da-pcloud.sh raccogli
# ESCLUDI: cartelle da saltare, relative a RADICE e separate da ":" (le scansioni
# in JPG sono foto per lo script: le cartelle di documenti vanno escluse). Si
# saltano sempre ARCHIVIO, "Crypto Folder" e le cartelle di sistema di Windows.
# Più radici: un raccogli per radice, prima di carica; i doppioni tra una e
# l'altra entrano una volta sola.
#
# Ogni fase si può rilanciare: riprende da dove era arrivata. Pulisci cancella solo
# gli originali elencati in LAVORO/fonti.tsv come copiati per intero; gli zip con
# dentro anche altri file (documenti, ...) e i file non letti restano dove sono,
# elencati in LAVORO/da-controllare.txt. Dopo carica, nell'app: "Indicizza archivio".
#
# Serve: unzip, zip, exiftool, rclone (sudo apt install unzip zip libimage-exiftool-perl rclone).
set -euo pipefail

FASE="${1:-stato}"
LAVORO="${LAVORO:-$HOME/foto-da-pcloud}"
IMPOSTAZIONI="$LAVORO/impostazioni"
mkdir -p "$LAVORO"
# Quello dato sulla riga di comando vale più di quello ricordato.
DATI_RADICE="${RADICE:-}" DATI_ARCHIVIO="${ARCHIVIO:-}" DATI_DESTINAZIONE="${DESTINAZIONE:-}" DATI_GIORNI="${GIORNI:-}"
DATI_ESCLUDI="${ESCLUDI-__nessuno__}" DATI_PCLOUD="${PCLOUD:-}"
if [ -f "$IMPOSTAZIONI" ]; then
    # shellcheck disable=SC1090
    . "$IMPOSTAZIONI"
fi
RADICE="${DATI_RADICE:-${RADICE:-}}"
ARCHIVIO="${DATI_ARCHIVIO:-${ARCHIVIO:-}}"
DESTINAZIONE="${DATI_DESTINAZIONE:-${DESTINAZIONE:-lifetime:FotoTimeline}}"
GIORNI="${DATI_GIORNI:-${GIORNI:-7}}"
[ "$DATI_ESCLUDI" != __nessuno__ ] && ESCLUDI="$DATI_ESCLUDI"
ESCLUDI="${ESCLUDI:-}"
PCLOUD="${DATI_PCLOUD:-${PCLOUD:-}}"
RCLONE="${RCLONE:-rclone}"
# File scaricati in parallelo da pCloud: con tante foto piccole il limite è l'attesa per
# file, non la banda (8 -> 24 ha portato una fibra da 8 a 29 MB/s).
TRASFERIMENTI="${TRASFERIMENTI:-24}"
export TZ="${TZ:-Europe/Rome}"

ORDINATE="$LAVORO/ordinate"
IMPRONTE="$LAVORO/impronte.tsv"          # sha256 \t percorso in ordinate/
MANIFEST="$LAVORO/manifest.tsv"          # fonte \t file nello zip (o vuoto) \t sha256 \t percorso in ordinate/
FONTI="$LAVORO/fonti.tsv"                # file|zip \t fonte \t si|no (si = si può cancellare)
DA_CONTROLLARE="$LAVORO/da-controllare.txt"
ARCHIVIATI="$LAVORO/archiviati.tsv"      # AAAA/MM \t zip su pCloud
RADICI="$LAVORO/radici.txt"              # le cartelle raccolte, una per riga
TMP="$LAVORO/tmp"
SPECCHIO="$LAVORO/specchio"              # copia locale di RADICE fatta da "scarica"
# Le funzioni comuni (raccogliere, caricare, archiviare) stanno in lib-foto.sh, accanto.
DOVE_ORIGINE="su pCloud" DOVE_ARCHIVIO="su pCloud" DATA_DAL_NOME="" ZIP_SUL_POSTO=""
COPIE_SCARICATE=""
SCARTA_NEI_ZIP=()
# shellcheck source=lib-foto.sh
. "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/lib-foto.sh"

# Il find delle radici: salta l'archivio, le cartelle escluse e quelle di sistema.
trova() {
    local radice="$1"; shift
    local -a salta=(-path "$ARCHIVIO" -o -name 'Crypto Folder' -o -name 'System Volume Information'
        -o -name '$RECYCLE.BIN' -o -name '.Trash*' -o -name '.pcloud*')
    local x
    local IFS=:
    for x in $ESCLUDI; do
        [ -n "$x" ] && salta+=(-o -path "$radice/${x%/}")
    done
    unset IFS
    find "$radice" \( "${salta[@]}" \) -prune -o "$@" 2>/dev/null
}

# ARCHIVIO è una cartella sua: né la radice né una cartella che la contiene.
controlla_archivio() {
    case "$RADICE/" in
        "$ARCHIVIO"/*) errore "ARCHIVIO ($ARCHIVIO) non può essere la radice o contenerla: usa una cartella nuova, per esempio '$RADICE/Archivio foto'" ;;
    esac
}

salva_impostazioni() {
    printf 'RADICE=%q\nARCHIVIO=%q\nESCLUDI=%q\nDESTINAZIONE=%q\nGIORNI=%q\nPCLOUD=%q\n' \
        "$RADICE" "$ARCHIVIO" "$ESCLUDI" "$DESTINAZIONE" "$GIORNI" "$PCLOUD" > "$IMPOSTAZIONI"
}

# Il remote rclone che corrisponde a RADICE: P: intero è "pcloud:", /mnt/p/Foto è "pcloud:Foto".
remote_pcloud() {
    if [ -n "$PCLOUD" ]; then
        printf '%s' "$PCLOUD"
    elif mountpoint -q "$RADICE" 2>/dev/null; then
        printf 'pcloud:'
    else
        printf 'pcloud:%s' "${RADICE#/mnt/p/}"
    fi
}

anteprima() {
    [ -n "$RADICE" ] || errore "manca RADICE: RADICE='/mnt/p' ARCHIVIO='/mnt/p/Archivio foto' $0 anteprima"
    [ -n "$ARCHIVIO" ] || errore "manca ARCHIVIO, la cartella di pCloud per gli zip ordinati"
    [ "$RADICE" != / ] && RADICE="${RADICE%/}"
    ARCHIVIO="${ARCHIVIO%/}"
    [ -d "$RADICE" ] || errore "$RADICE non c'è (pCloud montato? sudo mount -t drvfs P: /mnt/p)"
    controlla_archivio
    salva_impostazioni
    echo "Cerco in $RADICE (può volerci qualche minuto)…"
    echo "Salto: ${ARCHIVIO#"$RADICE"/}, Crypto Folder, cartelle di sistema${ESCLUDI:+, $ESCLUDI}"
    conta_media "$RADICE"
    date +%s > "$LAVORO/anteprima.ok"
    echo
    echo "Le cartelle di documenti (scansioni, ricevute in JPG) vanno escluse, per esempio:"
    echo "  ESCLUDI='TASSE:banche:avvocato' $0 anteprima"
    echo "Per vedere cosa c'è in una cartella:  find '$RADICE/NOME' -iname '*.jpg' | head"
    echo "Quando torna:  $0 raccogli"
}

scarica() {
    richiede "$RCLONE"
    [ -n "$RADICE" ] || errore "manca RADICE: prima $0 anteprima"
    [ -f "$LAVORO/anteprima.ok" ] || errore "prima: $0 anteprima (per escludere le cartelle di documenti)"
    PCLOUD="$(remote_pcloud)"
    salva_impostazioni
    # Le stesse esclusioni di trova(), come filtri di rclone.
    local filtri="$LAVORO/filtri-scarica.txt" x rel
    {
        rel="${ARCHIVIO#"$RADICE"/}"
        [ "$rel" != "$ARCHIVIO" ] && printf -- '- /%s/**\n' "$rel"
        printf -- '- Crypto Folder/**\n- System Volume Information/**\n- $RECYCLE.BIN/**\n- .Trash*/**\n- .pcloud*/**\n'
        local IFS=:
        for x in $ESCLUDI; do [ -n "$x" ] && printf -- '- /%s/**\n' "${x%/}"; done
        unset IFS
        printf -- '+ *.zip\n'
        for x in "${ESTENSIONI[@]}"; do printf -- '+ *.%s\n' "$x"; done
        printf -- '- *\n'
    } > "$filtri"
    mkdir -p "$SPECCHIO"
    echo "Scarico da $PCLOUD in $SPECCHIO (si può interrompere e rilanciare)…"
    rc copy "$PCLOUD" "$SPECCHIO" --filter-from "$filtri" --ignore-case \
        --transfers "$TRASFERIMENTI" --checkers $((TRASFERIMENTI + 8)) --stats-one-line --stats 30s --stats-log-level NOTICE
    date +%s > "$LAVORO/fase-scarica.ok"
    echo "Scaricato: $(du -sh "$SPECCHIO" | cut -f1). Prossimo passo: $0 raccogli"
}

# Da dove legge raccogli: la copia locale se scarica è finito, se no pCloud Drive.
base_lettura() {
    if [ -f "$LAVORO/fase-scarica.ok" ] && [ -d "$SPECCHIO" ]; then printf '%s' "$SPECCHIO"; else printf '%s' "$RADICE"; fi
}

raccogli() {
    richiede exiftool unzip sha256sum
    [ -n "$RADICE" ] || errore "manca RADICE: prima $0 anteprima"
    [ -n "$ARCHIVIO" ] || errore "manca ARCHIVIO, la cartella di pCloud per gli zip ordinati"
    [ "$RADICE" != / ] && RADICE="${RADICE%/}"
    ARCHIVIO="${ARCHIVIO%/}"
    BASE="$(base_lettura)"
    [ -d "$BASE" ] || errore "$BASE non c'è (pCloud montato? sudo mount -t drvfs P: /mnt/p)"
    if [ ! -f "$LAVORO/anteprima.ok" ]; then
        errore "prima: $0 anteprima (per vedere cosa prenderebbe ed escludere le cartelle di documenti)"
    fi
    controlla_archivio
    [ -f "$LAVORO/fase-archivia.ok" ] && errore "questo giro è già archiviato: per uno nuovo usa un altro LAVORO=..."
    salva_impostazioni
    touch "$RADICI"
    grep -Fxq -- "$RADICE" "$RADICI" || echo "$RADICE" >> "$RADICI"
    rm -f "$LAVORO/fase-carica.ok"
    mkdir -p "$ORDINATE"
    echo "Cerco foto, video e zip in $BASE (salto $ARCHIVIO)…"
    raccogli_fonti
}

carica() {
    carica_ordinate
    echo "Prossimo passo: $0 archivia"
}

archivia() {
    archivia_mesi
    echo "Ultimo passo, tra $GIORNI giorni: $0 pulisci"
}

pulisci() {
    [ -f "$LAVORO/fase-carica.ok" ] || errore "prima: $0 carica (e che finisca con il controllo a posto)"
    [ -f "$LAVORO/fase-archivia.ok" ] || errore "prima: $0 archivia"
    local ultimo adesso mancano
    ultimo=$(cat "$LAVORO/fase-carica.ok" "$LAVORO/fase-archivia.ok" | sort -n | tail -1)
    adesso=$(date +%s)
    mancano=$(( (ultimo + GIORNI * 86400 - adesso + 86399) / 86400 ))
    if [ "$mancano" -gt 0 ]; then
        echo "Troppo presto: mancano $mancano giorni (si può dal $(date -d "@$((ultimo + GIORNI * 86400))" '+%d/%m/%Y %H:%M'))."
        echo "Intanto guarda la timeline nell'app e gli zip in $ARCHIVIO."
        exit 0
    fi
    # Con scarica le cancellazioni passano da rclone (molto più veloce che da P:).
    local con_rclone=""
    [ -f "$LAVORO/fase-scarica.ok" ] && [ -n "$PCLOUD" ] && con_rclone=1
    local -a via=()
    local tipo fonte ok
    while IFS=$'\t' read -r tipo fonte ok; do
        [ "$ok" = si ] || continue
        if [ -n "$con_rclone" ] || [ -e "$fonte" ]; then via+=("$fonte"); fi
    done < "$FONTI"
    if [ "${#via[@]}" -eq 0 ]; then
        echo "Niente da cancellare."
        exit 0
    fi
    echo "Cancello da pCloud ${#via[@]} originali già in FotoTimeline e in $ARCHIVIO, per esempio:"
    printf '    %s\n' "${via[@]:0:10}"
    [ -s "$DA_CONTROLLARE" ] && echo "(restano su pCloud quelli in $DA_CONTROLLARE)"
    read -r -p "Scrivi CANCELLA per confermare: " risposta
    [ "$risposta" = CANCELLA ] || { echo "Non ho cancellato niente."; exit 0; }
    local f
    if [ -n "$con_rclone" ]; then
        pulisci_con_rclone "${via[@]}"
        return
    fi
    for f in "${via[@]}"; do rm -f -- "$f"; done
    # Solo le cartelle svuotate da qui, risalendo; mai una radice, e se la radice è
    # tutto pCloud nemmeno le cartelle in cima (Automatic Upload, My Pictures, ...).
    local c radice in_cima
    for f in "${via[@]}"; do
        c="$(dirname "$f")"
        while :; do
            in_cima=""
            while IFS= read -r radice; do
                if [ "$c" = "$radice" ] || [ "$c" = "/" ] || [ "$c" = "." ] \
                    || { mountpoint -q "$radice" 2>/dev/null && [ "$(dirname "$c")" = "$radice" ]; }; then
                    in_cima=1
                fi
            done < "$RADICI"
            [ -n "$in_cima" ] && break
            rmdir "$c" 2>/dev/null || break
            c="$(dirname "$c")"
        done
    done
    date +%s > "$LAVORO/fase-pulisci.ok"
    echo "Fatto. La copia di lavoro sul PC non serve più: rm -rf '$ORDINATE'"
}

# Il percorso rclone di una fonte (/mnt/p/Backups/x.jpg -> pcloud:Backups/x.jpg).
remoto_di() {
    local rel="${1#"$RADICE"/}"
    case "$PCLOUD" in *:) printf '%s%s' "$PCLOUD" "$rel" ;; *) printf '%s/%s' "$PCLOUD" "$rel" ;; esac
}

pulisci_con_rclone() {
    local lista="$LAVORO/da-cancellare.txt" f c
    for f in "$@"; do printf '%s\n' "${f#"$RADICE"/}"; done > "$lista"
    echo "Cancello $(wc -l < "$lista") file da $PCLOUD…"
    rc delete "$PCLOUD" --files-from-raw "$lista" --stats-one-line --stats 30s --stats-log-level NOTICE
    # Le cartelle svuotate, dal fondo: rmdir fallisce (e si salta) se non sono vuote.
    # Mai la radice né, con tutto pCloud come radice, le cartelle in cima.
    local -A cartelle
    for f in "$@"; do
        c="$(dirname "$f")"
        while [ "$c" != "$RADICE" ] && [ "$c" != / ] && [ "$c" != . ]; do
            if mountpoint -q "$RADICE" 2>/dev/null && [ "$(dirname "$c")" = "$RADICE" ]; then break; fi
            cartelle[$c]=1
            c="$(dirname "$c")"
        done
    done
    while IFS= read -r c; do
        [ -n "$c" ] && rc rmdir "$(remoto_di "$c")" 2>/dev/null || true
    done < <(printf '%s\n' "${!cartelle[@]}" | awk '{ print gsub("/", "/"), $0 }' | sort -rn | cut -d' ' -f2-)
    date +%s > "$LAVORO/fase-pulisci.ok"
    echo "Fatto. Le copie di lavoro sul PC non servono più: rm -rf '$ORDINATE' '$SPECCHIO'"
}

stato() {
    echo "Lavoro:      $LAVORO"
    if [ -s "$RADICI" ]; then
        sed '1s/^/Radici:      /; 2,$s/^/             /' "$RADICI"
    else
        echo "Radici:      (non ancora impostate)"
    fi
    echo "Archivio:    ${ARCHIVIO:-(non ancora impostato)}"
    echo "Destinaz.:   $DESTINAZIONE"
    if [ -f "$FONTI" ]; then
        echo "Raccolti:    $(wc -l < "$IMPRONTE") foto e video diversi da $(wc -l < "$FONTI") originali" \
            "($(grep -c $'\tno$' "$FONTI" || true) restano su pCloud)"
    else
        echo "Raccolti:    -"
    fi
    [ -f "$LAVORO/fase-scarica.ok" ] && echo "Scaricati:   $(data_di "$LAVORO/fase-scarica.ok") in $SPECCHIO" || echo "Scaricati:   - (consigliato: $0 scarica)"
    [ -f "$LAVORO/fase-carica.ok" ] && echo "Caricati:    $(data_di "$LAVORO/fase-carica.ok")" || echo "Caricati:    -"
    [ -f "$LAVORO/fase-archivia.ok" ] && echo "Archiviati:  $(data_di "$LAVORO/fase-archivia.ok")" || echo "Archiviati:  -"
    [ -f "$LAVORO/fase-pulisci.ok" ] && echo "Puliti:      $(data_di "$LAVORO/fase-pulisci.ok")" || echo "Puliti:      -"
}

case "$FASE" in
    anteprima) anteprima ;;
    scarica) scarica ;;
    raccogli) raccogli ;;
    carica) carica ;;
    archivia) archivia ;;
    pulisci) pulisci ;;
    stato) stato ;;
    *) errore "fase sconosciuta: $FASE (anteprima, scarica, raccogli, carica, archivia, pulisci, stato)" ;;
esac
