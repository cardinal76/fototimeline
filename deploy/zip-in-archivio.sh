#!/usr/bin/env bash
# Porta in FotoTimeline le foto chiuse in zip su un altro cloud (per esempio pCloud),
# già messe in AAAA/MM/GG secondo la data di scatto. Uno zip alla volta: sul disco
# serve spazio per uno zip e le sue foto, non per tutto l'archivio.
#
#   zip-in-archivio.sh 'pcloud:Cartella degli zip' [lifetime:FotoTimeline]
#
# Per ogni zip: lo scarica, lo apre, mette ogni foto e video in
# ordinate/AAAA/MM/GG (data EXIF, poi data di creazione del video, poi data del
# file nello zip), li copia nell'archivio e cancella tutto dal disco. Gli zip
# finiti si segnano in zip-fatti.txt: rilanciato, riparte da dove era arrivato.
# Un file diverso con lo stesso nome e la stessa cartella di uno già in archivio
# non lo sostituisce: il vecchio finisce in FotoTimeline-sovrascritte/.
#
# Dopo: nell'app, da amministratore, "Indicizza archivio".
#
# Serve: unzip, exiftool (sudo apt install unzip libimage-exiftool-perl) e
# docker; rclone gira nel suo container con la configurazione di ~/trasferimento.
# Sul PC (WSL), con pCloud Drive su P: e rclone installato:
#   RCLONE=rclone zip-in-archivio.sh '/mnt/p/Cartella degli zip'
# (SORGENTE può essere una cartella locale: rclone la legge come un remote.)
set -euo pipefail

SORGENTE="${1:?uso: $0 'pcloud:Cartella degli zip' [lifetime:FotoTimeline]}"
DESTINAZIONE="${2:-lifetime:FotoTimeline}"
BASE="${TRASFERIMENTO:-$HOME/trasferimento}"
LAVORO="$BASE/lavoro"
FATTI="$BASE/zip-fatti.txt"
export TZ="${TZ:-Europe/Rome}"
ESTENSIONI=(jpg jpeg png gif bmp webp heic heif mp4 m4v mov)

for comando in unzip exiftool; do
    command -v "$comando" >/dev/null || { echo "Manca $comando: sudo apt install unzip libimage-exiftool-perl" >&2; exit 1; }
done

# rclone nel container; i percorsi locali dentro sono sotto /lavoro.
# Con RCLONE=rclone (per esempio sul PC, in WSL) usa quello installato e la sua configurazione.
rc() {
    if [ -n "${RCLONE:-}" ]; then
        "$RCLONE" "${@//\/lavoro/$LAVORO}"
    else
        docker run --rm --user "$(id -u):$(id -g)" -e XDG_CACHE_HOME=/tmp \
            -v "$BASE:/config/rclone" -v "$LAVORO:/lavoro" rclone/rclone:1.68 "$@"
    fi
}

mkdir -p "$LAVORO"
touch "$FATTI"
mapfile -t ZIP < <(rc lsf --files-only --include '*.{zip,ZIP}' "$SORGENTE" | sort)
echo "${#ZIP[@]} zip in $SORGENTE"

argomenti_ext=()
for e in "${ESTENSIONI[@]}"; do argomenti_ext+=(-ext "$e"); done

n=0
for zip in "${ZIP[@]}"; do
    n=$((n + 1))
    if grep -Fxq -- "$zip" "$FATTI"; then
        echo "[$n/${#ZIP[@]}] $zip: già fatto"
        continue
    fi
    echo "[$n/${#ZIP[@]}] $zip: scarico"
    rm -rf "$LAVORO"/{zip,estratti,ordinate}
    mkdir -p "$LAVORO"/{zip,estratti,ordinate}
    rc copyto "$SORGENTE/$zip" /lavoro/zip/archivio.zip --stats-one-line --stats 30s </dev/null

    echo "[$n/${#ZIP[@]}] $zip: apro"
    # 1 = solo avvisi (per esempio nomi con caratteri strani): si va avanti.
    stato=0
    unzip -q -o "$LAVORO/zip/archivio.zip" -d "$LAVORO/estratti" </dev/null || stato=$?
    rm -f "$LAVORO/zip/archivio.zip"
    if [ "$stato" -gt 1 ]; then
        echo "[$n/${#ZIP[@]}] $zip: zip rovinato (unzip $stato), lo salto" >&2
        continue
    fi
    # Le copie "._nome" dei Mac non sono foto.
    rm -rf "$LAVORO"/estratti/__MACOSX
    find "$LAVORO/estratti" -name '._*' -type f -delete

    # L'ultima regola che trova la data vince: EXIF, poi data del video, poi data del file.
    # Le date vuote (0000, o 1904 nei video) non contano.
    exiftool -q -q -r -api QuickTimeUTC -d '%Y/%m/%d' "${argomenti_ext[@]}" \
        "-FileName<$LAVORO/ordinate/\${FileModifyDate;\$_=undef if /^(0000|1904)/}/%f%-c.%e" \
        "-FileName<$LAVORO/ordinate/\${CreateDate;\$_=undef if /^(0000|1904)/}/%f%-c.%e" \
        "-FileName<$LAVORO/ordinate/\${DateTimeOriginal;\$_=undef if /^(0000|1904)/}/%f%-c.%e" \
        "$LAVORO/estratti" </dev/null || true

    ordinate=$(find "$LAVORO/ordinate" -type f | wc -l)
    rimaste=$(find "$LAVORO/estratti" -type f | wc -l)
    echo "[$n/${#ZIP[@]}] $zip: $ordinate foto e video, $rimaste altri file lasciati fuori"
    if [ "$rimaste" -gt 0 ]; then
        find "$LAVORO/estratti" -type f | sed "s|^$LAVORO/estratti/|    fuori: |" | head -20
    fi

    if [ "$ordinate" -gt 0 ]; then
        echo "[$n/${#ZIP[@]}] $zip: copio in $DESTINAZIONE"
        rc copy /lavoro/ordinate "$DESTINAZIONE" --backup-dir "${DESTINAZIONE}-sovrascritte" \
            --transfers 4 --stats-one-line --stats 30s </dev/null
    fi
    rm -rf "$LAVORO"/{zip,estratti,ordinate}
    echo "$zip" >> "$FATTI"
done
echo "Fatto. Ora nell'app: Indicizza archivio."
