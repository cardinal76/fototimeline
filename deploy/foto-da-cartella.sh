#!/usr/bin/env bash
# Porta in FotoTimeline le foto e i video di una cartella del PC: per esempio la libreria
# di Amazon Foto scaricata con la sua app per Windows, vista da WSL in /mnt/c/... .
# Stessa logica di foto-da-pcloud.sh (lib-foto.sh), con una cartella di lavoro sua: non
# tocca né la cartella di origine né il lavoro di pCloud (~/foto-da-pcloud).
#
#   foto-da-cartella.sh anteprima  quante foto, video e zip ci sono in ogni cartella di SORGENTE
#   foto-da-cartella.sh raccogli   cerca foto e video in SORGENTE, anche dentro gli zip, e li mette
#                                  in LAVORO/ordinate/AAAA/MM/GG, senza doppioni
#   foto-da-cartella.sh carica     ordinate/ -> lifetime:FotoTimeline, poi controlla
#   foto-da-cartella.sh archivia   (facoltativo) ordinate/ -> ARCHIVIO/AAAA/AAAA-MM.zip, controllato
#   foto-da-cartella.sh stato      a che punto è
#
# La prima volta (poi si ricordano in LAVORO/impostazioni):
#   SORGENTE='/mnt/c/Users/marco/Pictures/Amazon Photos Downloads' foto-da-cartella.sh anteprima
#   foto-da-cartella.sh raccogli
#   foto-da-cartella.sh carica
#
# Impostazioni (sulla riga di comando, prima del nome dello script):
#   LAVORO        la cartella di lavoro (predefinita ~/foto-da-amazon); un LAVORO per giro
#   SORGENTE      la cartella da cui prendere le foto; più cartelle: un raccogli per cartella
#   ESCLUDI       cartelle da saltare, relative a SORGENTE e separate da ":"
#   ALTRI_LAVORI  altri LAVORO (separati da ":") già raccolti verso la stessa DESTINAZIONE,
#                 predefinito ~/foto-da-pcloud: le foto che hanno già non si riprendono e i
#                 loro nomi non si riusano. Si leggono soltanto. ALTRI_LAVORI='' per nessuno
#   DATA_DAL_NOME si (predefinito): senza EXIF la data si prende dal nome del file
#                 (IMG_20190101_..., IMG-20190101-WA0001, Screenshot 2019-01-01 ...) prima che
#                 dalla data del file, che dopo un download è spesso il giorno del download
#   DESTINAZIONE  predefinita lifetime:FotoTimeline
#   ARCHIVIO      solo per archivia: dove mettere uno zip per mese (vuota: niente archivia)
#
# Ogni fase si può rilanciare: riprende da dove era arrivata. La cartella di origine non si
# cancella mai: quando la timeline va bene, la si butta a mano. Dopo carica, nell'app:
# "Indicizza archivio".
#
# Serve: unzip, zip, exiftool, rclone (sudo apt install unzip zip libimage-exiftool-perl rclone).
set -euo pipefail

FASE="${1:-stato}"
LAVORO="${LAVORO:-$HOME/foto-da-amazon}"
IMPOSTAZIONI="$LAVORO/impostazioni"
mkdir -p "$LAVORO"
# Quello dato sulla riga di comando vale più di quello ricordato.
DATI_SORGENTE="${SORGENTE:-}" DATI_ARCHIVIO="${ARCHIVIO-__nessuno__}" DATI_DESTINAZIONE="${DESTINAZIONE:-}"
DATI_ESCLUDI="${ESCLUDI-__nessuno__}" DATI_ALTRI="${ALTRI_LAVORI-__nessuno__}" DATI_DAL_NOME="${DATA_DAL_NOME:-}"
if [ -f "$IMPOSTAZIONI" ]; then
    # shellcheck disable=SC1090
    . "$IMPOSTAZIONI"
fi
SORGENTE="${DATI_SORGENTE:-${SORGENTE:-}}"
[ "$DATI_ARCHIVIO" != __nessuno__ ] && ARCHIVIO="$DATI_ARCHIVIO"
ARCHIVIO="${ARCHIVIO:-}"
DESTINAZIONE="${DATI_DESTINAZIONE:-${DESTINAZIONE:-lifetime:FotoTimeline}}"
[ "$DATI_ESCLUDI" != __nessuno__ ] && ESCLUDI="$DATI_ESCLUDI"
ESCLUDI="${ESCLUDI:-}"
[ "$DATI_ALTRI" != __nessuno__ ] && ALTRI_LAVORI="$DATI_ALTRI"
ALTRI_LAVORI="${ALTRI_LAVORI-$HOME/foto-da-pcloud}"
DATA_DAL_NOME="${DATI_DAL_NOME:-${DATA_DAL_NOME:-si}}"
RCLONE="${RCLONE:-rclone}"
export TZ="${TZ:-Europe/Rome}"

ORDINATE="$LAVORO/ordinate"
IMPRONTE="$LAVORO/impronte.tsv"          # sha256 \t percorso in ordinate/
MANIFEST="$LAVORO/manifest.tsv"          # fonte \t file nello zip (o vuoto) \t sha256 \t percorso in ordinate/ (o in un altro LAVORO)
FONTI="$LAVORO/fonti.tsv"                # file|zip \t fonte \t si|no (si = preso per intero)
DA_CONTROLLARE="$LAVORO/da-controllare.txt"
ARCHIVIATI="$LAVORO/archiviati.tsv"      # AAAA/MM \t zip in ARCHIVIO
RADICI="$LAVORO/radici.txt"              # le cartelle raccolte, una per riga
TMP="$LAVORO/tmp"
SPECCHIO="$LAVORO/specchio"              # non usata qui: la cartella di origine è già sul PC
# Una cartella del PC: gli zip si aprono dove sono. Nei zip scaricati, i file di Windows e
# dei Mac non sono "altri file" da controllare.
DOVE_ORIGINE="nella cartella di origine" DOVE_ARCHIVIO="nell'archivio" ZIP_SUL_POSTO=si
# Nei download di Windows "IMG_0001 (1).jpg" è un doppione: si torna a "IMG_0001.jpg".
COPIE_SCARICATE=si
SCARTA_NEI_ZIP=(Thumbs.db desktop.ini .DS_Store)
# lib-foto.sh legge RADICE: qui è la SORGENTE di questo giro.
RADICE="$SORGENTE"
# shellcheck source=lib-foto.sh
. "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/lib-foto.sh"

# Il find della sorgente: salta le cartelle escluse, quelle di sistema, LAVORO e ARCHIVIO.
trova() {
    local radice="$1"; shift
    # shellcheck disable=SC2016
    local -a salta=(-path "$LAVORO" -o -name 'System Volume Information'
        -o -name '$RECYCLE.BIN' -o -name '.Trash*')
    [ -n "$ARCHIVIO" ] && salta+=(-o -path "$ARCHIVIO")
    local x
    local IFS=:
    for x in $ESCLUDI; do
        [ -n "$x" ] && salta+=(-o -path "$radice/${x%/}")
    done
    unset IFS
    find "$radice" \( "${salta[@]}" \) -prune -o "$@" 2>/dev/null
}

salva_impostazioni() {
    printf 'SORGENTE=%q\nARCHIVIO=%q\nESCLUDI=%q\nDESTINAZIONE=%q\nALTRI_LAVORI=%q\nDATA_DAL_NOME=%q\n' \
        "$SORGENTE" "$ARCHIVIO" "$ESCLUDI" "$DESTINAZIONE" "$ALTRI_LAVORI" "$DATA_DAL_NOME" > "$IMPOSTAZIONI"
}

controlla_sorgente() {
    [ -n "$SORGENTE" ] || errore "manca SORGENTE: SORGENTE='/mnt/c/Users/NOME/Pictures/Amazon Photos Downloads' $0 anteprima"
    [ "$SORGENTE" != / ] && SORGENTE="${SORGENTE%/}"
    ARCHIVIO="${ARCHIVIO%/}"
    RADICE="$SORGENTE"
    [ -d "$SORGENTE" ] || errore "$SORGENTE non c'è (il percorso di Windows C:\\Users\\... in WSL è /mnt/c/Users/...)"
    case "$LAVORO/" in
        "$SORGENTE"/*) errore "LAVORO ($LAVORO) non può stare dentro SORGENTE" ;;
    esac
    case "$SORGENTE/" in
        "$LAVORO"/*) errore "SORGENTE ($SORGENTE) non può stare dentro LAVORO ($LAVORO)" ;;
    esac
    if [ -n "$ARCHIVIO" ]; then
        case "$SORGENTE/" in
            "$ARCHIVIO"/*) errore "ARCHIVIO ($ARCHIVIO) non può essere SORGENTE o contenerla" ;;
        esac
    fi
}

anteprima() {
    controlla_sorgente
    salva_impostazioni
    echo "Cerco in $SORGENTE (può volerci qualche minuto)…"
    echo "Salto: cartelle di sistema${ESCLUDI:+, $ESCLUDI}"
    conta_media "$SORGENTE"
    date +%s > "$LAVORO/anteprima.ok"
    echo
    echo "Per saltare delle cartelle:  ESCLUDI='Documenti:Scansioni' $0 anteprima"
    echo "Quando torna:  $0 raccogli"
}

# I lavori già fatti verso la stessa destinazione (pCloud): doppioni e nomi presi.
carica_altri_lavori() {
    local x
    local IFS=:
    for x in $ALTRI_LAVORI; do
        [ -n "$x" ] || continue
        x="${x%/}"
        [ "$x" = "${LAVORO%/}" ] && continue
        if [ -f "$x/impronte.tsv" ]; then
            carica_impronte_di "$x/impronte.tsv"
            echo "Già raccolte in $x: $(wc -l < "$x/impronte.tsv") foto e video (non si riprendono)"
        fi
    done
    unset IFS
}

raccogli() {
    richiede exiftool unzip sha256sum
    controlla_sorgente
    [ -f "$LAVORO/fase-archivia.ok" ] && errore "questo giro è già archiviato: per uno nuovo usa un altro LAVORO=..."
    salva_impostazioni
    BASE="$SORGENTE"
    touch "$RADICI"
    grep -Fxq -- "$SORGENTE" "$RADICI" || echo "$SORGENTE" >> "$RADICI"
    rm -f "$LAVORO/fase-carica.ok"
    mkdir -p "$ORDINATE"
    carica_altri_lavori
    echo "Cerco foto, video e zip in $SORGENTE…"
    raccogli_fonti
    local gia
    gia=$(awk -F'\t' 'NR == FNR { mio[$2] = 1; next } !($4 in mio) { n++ } END { print n + 0 }' "$IMPRONTE" "$MANIFEST")
    [ "$gia" -gt 0 ] && echo "Doppioni di foto già raccolte in $ALTRI_LAVORI (non ricopiati): $gia"
    giorni_sospetti
    return 0
}

# Senza EXIF né data nel nome resta la data del file, che dopo un download può essere
# il giorno del download: i giorni recenti con dentro qualcosa si fanno vedere.
giorni_sospetti() {
    local da righe
    da=$(date -d '-60 days' +%Y/%m/%d)
    righe=$(cd "$ORDINATE" && find . -mindepth 3 -maxdepth 3 -type d | sed 's|^\./||' | sort \
        | awk -v da="$da" '$0 >= da' | while IFS= read -r g; do
            printf '    %s  %s file\n' "$g" "$(find "$g" -type f | wc -l)"
        done)
    if [ -n "$righe" ]; then
        echo "Giorni degli ultimi 60 giorni in $ORDINATE: se non sono foto di quei giorni,"
        echo "hanno la data del download (niente EXIF né data nel nome). Guardali prima di carica:"
        echo "$righe"
    fi
}

carica() {
    carica_ordinate
    if [ -n "$ARCHIVIO" ]; then
        echo "Prossimo passo (facoltativo): $0 archivia"
    else
        echo "Fatto. La cartella di origine resta com'è: buttala tu quando la timeline va bene."
    fi
}

archivia() {
    [ -n "$ARCHIVIO" ] || errore "manca ARCHIVIO: ARCHIVIO='/mnt/p/Archivio foto' $0 archivia (vedi stato)"
    ARCHIVIO="${ARCHIVIO%/}"
    salva_impostazioni
    archivia_mesi
}

stato() {
    echo "Lavoro:      $LAVORO"
    if [ -s "$RADICI" ]; then
        sed '1s/^/Sorgenti:    /; 2,$s/^/             /' "$RADICI"
    else
        echo "Sorgenti:    ${SORGENTE:-(non ancora impostata)}"
    fi
    echo "Destinaz.:   $DESTINAZIONE"
    echo "Archivio:    ${ARCHIVIO:-(nessuno)}"
    echo "Altri lavori: ${ALTRI_LAVORI:-(nessuno)}"
    echo "Data dal nome: $DATA_DAL_NOME"
    if [ -f "$FONTI" ] && [ -f "$IMPRONTE" ]; then
        echo "Raccolti:    $(wc -l < "$IMPRONTE") foto e video diversi da $(wc -l < "$FONTI") originali" \
            "($(grep -c $'\tno$' "$FONTI" || true) da controllare)"
    else
        echo "Raccolti:    -"
    fi
    [ -f "$LAVORO/fase-carica.ok" ] && echo "Caricati:    $(data_di "$LAVORO/fase-carica.ok")" || echo "Caricati:    -"
    [ -f "$LAVORO/fase-archivia.ok" ] && echo "Archiviati:  $(data_di "$LAVORO/fase-archivia.ok")" || echo "Archiviati:  -"
}

case "$FASE" in
    anteprima) anteprima ;;
    raccogli) raccogli ;;
    carica) carica ;;
    archivia) archivia ;;
    stato) stato ;;
    *) errore "fase sconosciuta: $FASE (anteprima, raccogli, carica, archivia, stato)" ;;
esac
