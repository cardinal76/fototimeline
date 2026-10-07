#!/usr/bin/env bash
# Test di foto-da-pcloud.sh e foto-da-cartella.sh su file di prova, tutto in una cartella
# temporanea: niente rete, niente pCloud né LifetimeCloud (rclone copia tra cartelle locali),
# e la vera ~/foto-da-pcloud non si tocca (HOME è finta).
#
#   ./deploy/test-foto.sh
#
# 1. foto-da-pcloud.sh di adesso contro quella di RIFERIMENTO (il commit prima di
#    lib-foto.sh, da git): stesse uscite e stessi file in ogni fase, con e senza scarica.
# 2. foto-da-cartella.sh su una finta libreria di Amazon Foto: foto sciolte e zip, doppioni
#    " (1)", album, data dal nome, file senza data, doppioni e nomi del lavoro di pCloud.
# 3. PARALLELI: 1100 file sciolti e 12 zip (foto in comune, nomi uguali con foto diverse, uno
#    zip rovinato, uno con altri file): con PARALLELI=1, 3 e 4 stessa uscita e stessi file;
#    con PARALLELI=4 ucciso a metà più volte e rilanciato, stesso risultato del giro intero.
#
# Serve: exiftool, rclone, zip, unzip (ffmpeg facoltativo, per un video vero).
set -uo pipefail

QUI="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
RIFERIMENTO="${RIFERIMENTO:-c34f8b9}"
for c in exiftool rclone zip unzip sha256sum base64; do
    command -v "$c" >/dev/null || { echo "manca $c"; exit 2; }
done

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
export HOME="$T/casa" TZ=Europe/Rome RCLONE_CONFIG="$T/rclone.conf"
unset LAVORO RADICE ARCHIVIO ESCLUDI DESTINAZIONE GIORNI PCLOUD SORGENTE ALTRI_LAVORI DATA_DAL_NOME
mkdir -p "$HOME" "$T/bin"
: > "$RCLONE_CONFIG"

OK=0 KO=0
bene() { OK=$((OK + 1)); echo "  ok  $1"; }
male() { KO=$((KO + 1)); echo "  NO  $1"; }
verifica() { local d="$1"; shift; if "$@"; then bene "$d"; else male "$d"; fi; }
c_e() { [ -f "$1" ]; }
non_c_e() { [ ! -e "$1" ]; }
contiene() { grep -Fq -- "$2" "$1"; }

# Un JPEG di 1x1; ogni copia ha in coda un testo suo, così ha un'impronta sua.
JPG='/9j/4AAQSkZJRgABAQAAAAAAAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hnPk1xeXBkeFxlZ2P/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAB//EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AQH//2Q=='
jpg() {   # jpg FILE TESTO [DATA_EXIF] [MTIME]
    mkdir -p "$(dirname "$1")"
    base64 -d <<< "$JPG" > "$1"
    [ -n "${3:-}" ] && exiftool -q -overwrite_original -DateTimeOriginal="$3" "$1"
    printf '%s' "$2" >> "$1"
    [ -n "${4:-}" ] && touch -d "$4" "$1"
    return 0
}
finto() { mkdir -p "$(dirname "$1")"; printf 'finto %s' "$2" > "$1"; touch -d "$3" "$1"; }

# --- 1. foto-da-pcloud.sh: prima e dopo lib-foto.sh --------------------------------------
crea_pcloud() {
    local r="$1"
    jpg "$r/Foto/2015/a.jpg" a '2015:05:06 07:08:09' '2020-01-01 10:00'
    cp -p "$r/Foto/2015/a.jpg" "$r/Foto/copia-a.jpg"
    jpg "$r/Foto/b.jpg" b '' '2012-03-04 12:00'
    finto "$r/Foto/c.png" c '2013-01-01 12:00'
    jpg "$r/Altro/a.jpg" a-diversa '2015:05:06 09:00:00' '2020-01-01 10:00'
    jpg "$r/Altro/IMG_20190101_120000.jpg" nome '' '2020-02-02 12:00'
    finto "$r/Video/v.mp4" v '2014-04-04 12:00'
    jpg "$r/TASSE/scansione.jpg" tasse '' '2010-01-01 12:00'
    finto "$r/note.txt" note '2010-01-01 12:00'
    mkdir -p "$T/z"
    jpg "$T/z/x.jpg" x '2016:07:08 10:00:00' '2016-07-08 10:00'
    cp -p "$r/Foto/2015/a.jpg" "$T/z/y.jpg"
    mkdir -p "$r/Zip"
    (cd "$T/z" && zip -q -X "$r/Zip/vacanze.zip" x.jpg y.jpg)
    jpg "$T/z/z.jpg" z '2017:01:02 10:00:00' '2017-01-02 10:00'
    finto "$T/z/nota.txt" nota '2017-01-02 10:00'
    (cd "$T/z" && zip -q -X "$r/Zip/misto.zip" z.jpg nota.txt)
    printf 'non sono uno zip' > "$r/Zip/rotto.zip"
    touch -d '2018-01-01 10:00' "$r/Zip/"*.zip
    rm -rf "$T/z"
}

# Gira tutte le fasi; in $3 l'uscita (senza orari e righe di rclone) e lo stato dei file.
gira_pcloud() {   # gira_pcloud SCRIPT con|senza USCITA [SCRIPT dal secondo raccogli in poi]
    local script="$1" modo="$2" out="$3" dopo="${4:-$1}" r="$T/p" l="$HOME/foto-da-pcloud" s="$T/bin/foto-da-pcloud.sh"
    rm -rf "$r" "$l" "$T/dest" "$T/dest-sovrascritte"
    cp -a "$T/p-modello" "$r"
    ln -sf "$script" "$s"
    {
        RADICE="$r" ARCHIVIO="$r/Archivio foto" GIORNI=0 "$s" anteprima
        ESCLUDI=TASSE DESTINAZIONE="$T/dest" "$s" anteprima
        [ "$modo" = con ] && PCLOUD="$r" "$s" scarica
        "$s" raccogli
        ln -sf "$dopo" "$s"
        echo "--- di nuovo"; "$s" raccogli
        "$s" stato
        "$s" carica
        "$s" archivia
        echo CANCELLA | "$s" pulisci
        "$s" stato
        echo "--- fase sbagliata"; "$s" boh
    } > "$out.grezza" 2>&1
    grep -Ev '^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9:]+ (NOTICE|INFO|ERROR)|Transferred:|Checks:|Elapsed time' "$out.grezza" \
        | sed -E 's|[0-9]{2}/[0-9]{2}/[0-9]{4} [0-9]{2}:[0-9]{2}|DATA|g' > "$out"
    {
        echo "== file di lavoro"
        (cd "$l" && for f in impostazioni impronte.tsv manifest.tsv fonti.tsv da-controllare.txt archiviati.tsv radici.txt filtri-scarica.txt da-cancellare.txt; do
            [ -f "$f" ] && { echo "-- $f"; cat "$f"; }
        done; ls)
        echo "== ordinate";     (cd "$l/ordinate" && find . -type f -exec sha256sum {} + | sort -k2)
        echo "== destinazione"; (cd "$T/dest" && find . -type f -exec sha256sum {} + | sort -k2)
        echo "== pcloud dopo";  (cd "$r" && find . | sort)
        echo "== zip mesi";     (cd "$r/Archivio foto" && find . -name '*.zip' -exec unzip -Z1 {} \; | sort)
    } > "$out.file" 2>&1
}

echo "1. foto-da-pcloud.sh"
mkdir -p "$T/p-modello"
crea_pcloud "$T/p-modello"
mkdir -p "$T/rif"
if git -C "$QUI" show "$RIFERIMENTO:deploy/foto-da-pcloud.sh" > "$T/rif/foto-da-pcloud.sh" 2>/dev/null; then
    chmod +x "$T/rif/foto-da-pcloud.sh"
    for modo in senza con; do
        gira_pcloud "$T/rif/foto-da-pcloud.sh" "$modo" "$T/rif-$modo"
        gira_pcloud "$QUI/foto-da-pcloud.sh" "$modo" "$T/ora-$modo"
        if diff -u "$T/rif-$modo" "$T/ora-$modo" > "$T/diff" && diff -u "$T/rif-$modo.file" "$T/ora-$modo.file" >> "$T/diff"; then
            bene "$modo scarica: uscita e file identici a $RIFERIMENTO"
        else
            male "$modo scarica: diverso da $RIFERIMENTO"; head -60 "$T/diff"
        fi
    done
    # Un giro cominciato con la versione vecchia (come quello in corso) e finito con questa.
    gira_pcloud "$T/rif/foto-da-pcloud.sh" con "$T/misto" "$QUI/foto-da-pcloud.sh"
    if diff -u "$T/rif-con" "$T/misto" > "$T/diff" && diff -u "$T/rif-con.file" "$T/misto.file" >> "$T/diff"; then
        bene "giro cominciato con $RIFERIMENTO e finito con questa: identico"
    else
        male "giro misto diverso"; head -60 "$T/diff"
    fi
    gira_pcloud "$QUI/foto-da-pcloud.sh" con "$T/ora-con"
else
    echo "  (salto il confronto: $RIFERIMENTO non c'è in git)"
    gira_pcloud "$QUI/foto-da-pcloud.sh" con "$T/ora-con"
fi
O="$HOME/foto-da-pcloud/ordinate"
verifica "EXIF: 2015/05/06/a.jpg"                      c_e "$O/2015/05/06/a.jpg"
verifica "stesso nome e giorno, foto diversa: a-1.jpg" c_e "$O/2015/05/06/a-1.jpg"
verifica "senza EXIF: data del file (2012/03/04)"      c_e "$O/2012/03/04/b.jpg"
verifica "pCloud non usa la data nel nome (come prima)" c_e "$O/2020/02/02/IMG_20190101_120000.jpg"
verifica "video: 2014/04/04/v.mp4"                     c_e "$O/2014/04/04/v.mp4"
verifica "dallo zip: 2016/07/08/x.jpg"                 c_e "$O/2016/07/08/x.jpg"
verifica "doppione (copia e nello zip) una volta sola" [ "$(find "$O" -type f | wc -l)" -eq 8 ]
verifica "esclusa TASSE"                               non_c_e "$O/2010"
verifica "zip misto resta"                             contiene "$HOME/foto-da-pcloud/fonti.tsv" $'misto.zip\tno'
verifica "zip rovinato segnato"                        contiene "$HOME/foto-da-pcloud/da-controllare.txt" "zip rovinato"
verifica "pulisci: tolto lo zip delle vacanze"         non_c_e "$T/p/Zip/vacanze.zip"
verifica "pulisci: resta lo zip misto"                 c_e "$T/p/Zip/misto.zip"
verifica "pulisci: resta TASSE"                        c_e "$T/p/TASSE/scansione.jpg"

# --- 1b. nomi troppo lunghi per il disco (qui il limite è abbassato a 60 byte) ---------------
L="$T/lunghi" LL="$HOME/foto-da-pcloud-lunghi"
LUNGO="Auguri alla piccola di casa è già un anno che ci conosciamo #compleanno ;-) ok"
jpg "$L/Foto/$LUNGO.jpg" lungo '2019:03:18 10:00:00' '2019-03-18 10:00'
jpg "$L/Foto/corto.jpg" corto '2019:03:19 10:00:00' '2019-03-19 10:00'
{
    s="$QUI/foto-da-pcloud.sh"
    export LAVORO="$LL" NOME_MAX=60 NOME_CORTO=20
    RADICE="$L" ARCHIVIO="$L/Archivio foto" GIORNI=0 DESTINAZIONE="$T/dest-lunghi" "$s" anteprima
    PCLOUD="$L" "$s" scarica
    "$s" raccogli
    "$s" carica
    "$s" archivia
    echo CANCELLA | "$s" pulisci
    unset LAVORO NOME_MAX NOME_CORTO
} > "$T/lunghi.txt" 2>&1
CORTO="$(cut -f1 "$LL/nomi-lunghi.tsv" 2>/dev/null)"
verifica "nome lungo scaricato accorciato ($CORTO)"   c_e "$LL/specchio/$CORTO"
verifica "accorciato: al massimo 60 byte"             [ "$(printf '%s' "$(basename "$CORTO")" | LC_ALL=C wc -c)" -le 60 ]
verifica "accorciato: lettere accentate intere"       iconv -f UTF-8 -t UTF-8 <<< "$CORTO"
verifica "nome corto scaricato com'è"                 c_e "$LL/specchio/Foto/corto.jpg"
verifica "accorciato in ordinate"                     c_e "$LL/ordinate/2019/03/18/$(basename "$CORTO")"
verifica "fonte col nome vero di pCloud"              contiene "$LL/fonti.tsv" $'file\t'"$L/Foto/$LUNGO.jpg"$'\tsi'
verifica "carica: in destinazione"                    c_e "$T/dest-lunghi/2019/03/18/$(basename "$CORTO")"
verifica "pulisci: tolto l'originale col nome lungo"  non_c_e "$L/Foto/$LUNGO.jpg"
verifica "pulisci: tolto anche quello corto"          non_c_e "$L/Foto/corto.jpg"
[ "$KO" -gt 0 ] && cat "$T/lunghi.txt"

# --- 2. foto-da-cartella.sh: una finta libreria di Amazon Foto -----------------------------
echo "2. foto-da-cartella.sh"
S="$T/c/Users/marco/Pictures/Amazon Photos Downloads"
OGGI="$(date '+%Y-%m-%d %H:%M')"
jpg "$S/2019/IMG_20190101_120000.jpg" capodanno '' "$OGGI"
cp "$S/2019/IMG_20190101_120000.jpg" "$S/2019/IMG_20190101_120000 (1).jpg"
jpg "$S/2019/IMG_20190202_100000 (1).jpg" altra '' "$OGGI"
mkdir -p "$S/Album Mare"; cp "$S/2019/IMG_20190101_120000.jpg" "$S/Album Mare/"
jpg "$S/2020/IMG_20200101_090000.jpg" exif-vince '2015:05:06 11:00:00' "$OGGI"
jpg "$S/2018/IMG-20180305-WA0001.jpg" whatsapp '' "$OGGI"
finto "$S/2017/Screenshot 2017-07-08 at 10.11.12.png" schermata "$OGGI"
jpg "$S/2016/a.jpg" a-terza '2015:05:06 12:00:00' "$OGGI"
cp "$HOME/foto-da-pcloud/ordinate/2016/07/08/x.jpg" "$S/2016/gia-in-pcloud.jpg"
jpg "$S/vecchia.jpg" vecchia '' '2011-01-01 12:00'
jpg "$S/scaricata-oggi.jpg" senza-data '' "$OGGI"
finto "$S/2021/IMG_1234.HEIC" heic '2021-05-05 12:00'
if command -v ffmpeg >/dev/null; then
    ffmpeg -loglevel error -f lavfi -i color=c=red:s=16x16:d=0.1 -metadata creation_time=2016-06-01T10:00:00Z \
        -y "$S/2021/clip.mov" </dev/null && touch -d "$OGGI" "$S/2021/clip.mov"
    VIDEO=2016/06/01/clip.mov
else
    finto "$S/2021/clip.mov" clip '2021-06-06 12:00'
    VIDEO=2021/06/06/clip.mov
fi
finto "$S/metadata.json" '{}' "$OGGI"
finto "$S/desktop.ini" ini "$OGGI"
finto "$S/2019/Thumbs.db" db "$OGGI"
jpg "$S/Documenti/scansione.jpg" doc '' '2010-01-01 12:00'
mkdir -p "$T/z"
jpg "$T/z/AmazonPhotos/p1.jpg" dallo-zip '2014:01:02 10:00:00' "$OGGI"
finto "$T/z/AmazonPhotos/Thumbs.db" db "$OGGI"
finto "$T/z/AmazonPhotos/desktop.ini" ini "$OGGI"
(cd "$T/z" && zip -q -r -X "$S/AmazonPhotos.zip" AmazonPhotos)
jpg "$T/z/b2/IMG_20190101_120000.jpg" capodanno '' "$OGGI"
(cd "$T/z/b2" && zip -q -X "$S/AmazonPhotos (1).zip" IMG_20190101_120000.jpg)

impronte_pcloud() { (cd "$HOME/foto-da-pcloud" && find . -type f -exec sha256sum {} + | sort -k2); }
prima="$(impronte_pcloud)"
A="$HOME/foto-da-amazon"
s="$QUI/foto-da-cartella.sh"
{
    SORGENTE="$S" ESCLUDI=Documenti DESTINAZIONE="$T/dest" "$s" anteprima
    "$s" raccogli
    echo "--- di nuovo"; "$s" raccogli
    "$s" carica
    ARCHIVIO="$T/archivio-amazon" "$s" archivia
    "$s" stato
} > "$T/cartella.txt" 2>&1
O="$A/ordinate"
verifica "data dal nome: 2019/01/01"                     c_e "$O/2019/01/01/IMG_20190101_120000.jpg"
verifica "\" (1)\", album e zip: una volta sola"          [ "$(find "$O/2019/01" -type f | wc -l)" -eq 1 ]
verifica "\" (1)\" diversa: senza \" (1)\" nel nome"     c_e "$O/2019/02/02/IMG_20190202_100000.jpg"
verifica "l'EXIF vale più del nome: 2015/05/06"          c_e "$O/2015/05/06/IMG_20200101_090000.jpg"
verifica "WhatsApp: 2018/03/05"                          c_e "$O/2018/03/05/IMG-20180305-WA0001.jpg"
verifica "Screenshot: 2017/07/08"                        c_e "$O/2017/07/08/Screenshot 2017-07-08 at 10.11.12.png"
verifica "nome preso nel lavoro di pCloud: a_2.jpg"      c_e "$O/2015/05/06/a_2.jpg"
verifica "a.jpg non rifatto qui"                         non_c_e "$O/2015/05/06/a.jpg"
verifica "a-1.jpg non rifatto qui"                       non_c_e "$O/2015/05/06/a-1.jpg"
verifica "doppione di pCloud non ricopiato"              non_c_e "$O/2016/07/08"
verifica "doppione di pCloud nel manifest"               contiene "$A/manifest.tsv" $'\t2016/07/08/x.jpg'
verifica "senza date: data del file (2011/01/01)"        c_e "$O/2011/01/01/vecchia.jpg"
verifica "HEIC preso"                                    c_e "$O/2021/05/05/IMG_1234.HEIC"
verifica "video .mov: $VIDEO"                            c_e "$O/$VIDEO"
verifica "dallo zip: 2014/01/02/p1.jpg"                  c_e "$O/2014/01/02/p1.jpg"
verifica "zip con Thumbs.db e desktop.ini preso intero"  contiene "$A/fonti.tsv" $'AmazonPhotos.zip\tsi'
verifica "niente da controllare"                         [ ! -s "$A/da-controllare.txt" ]
verifica "esclusa Documenti"                             non_c_e "$O/2010"
verifica "avviso sui giorni recenti"                     contiene "$T/cartella.txt" "$(date +%Y/%m/%d)  1 file"
verifica "rilancio: niente da fare"                      contiene "$T/cartella.txt" "0 foto e video sciolti e 0 zip da fare"
verifica "doppioni di pCloud contati"                    contiene "$T/cartella.txt" "(non ricopiati): 1"
verifica "carica: controllo a posto"                     c_e "$A/fase-carica.ok"
verifica "carica: in destinazione"                       c_e "$T/dest/2019/01/01/IMG_20190101_120000.jpg"
verifica "carica: a.jpg di pCloud intatta"               cmp -s "$T/dest/2015/05/06/a.jpg" "$HOME/foto-da-pcloud/ordinate/2015/05/06/a.jpg"
verifica "archivia: zip del mese"                        c_e "$T/archivio-amazon/2019/2019-01.zip"
verifica "lavoro di pCloud non toccato"                  [ "$prima" = "$(impronte_pcloud)" ]
verifica "sorgente non toccata"                          c_e "$S/2019/IMG_20190101_120000 (1).jpg"
verifica "impostazioni ricordate"                        contiene "$A/impostazioni" "ESCLUDI=Documenti"

# --- 3. PARALLELI: in fila o in parallelo, stessi file; e ripresa dopo un kill a metà ----
echo "3. PARALLELI"
M="$T/molti"
base64 -d <<< "$JPG" > "$T/vuota.jpg"
cp "$T/vuota.jpg" "$T/exif-a.jpg"; exiftool -q -overwrite_original -DateTimeOriginal='2016:07:08 10:00:00' "$T/exif-a.jpg"
cp "$T/vuota.jpg" "$T/exif-b.jpg"; exiftool -q -overwrite_original -DateTimeOriginal='2017:03:04 10:00:00' "$T/exif-b.jpg"
veloce() {   # veloce FILE MODELLO TESTO MTIME: come jpg, senza un exiftool per file
    mkdir -p "$(dirname "$1")"
    { cat "$2"; printf '%s' "$3"; } > "$1"
    touch -d "$4" "$1"
}
# 1100 file sciolti (tre blocchi da 500): nomi che si ripetono in cartelle diverse, con
# contenuto diverso e lo stesso giorno, e qualche copia identica.
for ((i = 0; i < 1100; i++)); do
    veloce "$M/Sciolti/c$((i % 9))/IMG_$((i % 40)).jpg" "$T/vuota.jpg" "s$i" "2012-01-0$((i % 5 + 1)) 12:00"
done
cp -p "$M/Sciolti/c1/IMG_1.jpg" "$M/Sciolti/copia-di-IMG_1.jpg"
# 12 zip: in ognuno IMG_1..IMG_20 con contenuto suo (nomi uguali, foto diverse, stesse
# date), alcune foto uguali a quelle di altri zip, alcune uguali ai file sciolti.
for ((z = 1; z <= 12; z++)); do
    d="$T/zz/$z"
    for ((i = 1; i <= 20; i++)); do
        case $((i % 3)) in
            0) veloce "$d/IMG_$i.jpg" "$T/exif-a.jpg" "z$z-$i" '2020-01-01 10:00' ;;
            1) veloce "$d/IMG_$i.jpg" "$T/exif-b.jpg" "z$z-$i" '2020-01-01 10:00' ;;
            *) veloce "$d/sub/IMG_$i.jpg" "$T/vuota.jpg" "z$z-$i" "2013-0$((i % 4 + 1))-01 10:00" ;;
        esac
    done
    veloce "$d/comune.jpg" "$T/exif-a.jpg" "in tutti gli zip" '2020-01-01 10:00'
    [ $((z % 4)) = 0 ] && veloce "$d/meta.jpg" "$T/exif-b.jpg" "z$((z - 1))-1" '2020-01-01 10:00'
    cp -p "$M/Sciolti/c$((z % 9))/IMG_$z.jpg" "$d/dagli-sciolti.jpg"
    [ "$z" = 5 ] && finto "$d/nota.txt" nota '2017-01-02 10:00'
    mkdir -p "$M/Zip/z$((z % 3))"
    (cd "$d" && zip -q -r -X "$M/Zip/z$((z % 3))/album-$z.zip" .)
done
printf 'rovinato' > "$M/Zip/z1/rovinato.zip"
touch -d '2018-01-01 10:00' "$M"/Zip/*/*.zip
rm -rf "$T/zz"

# raccogli con un PARALLELI su una copia di $M; in $2 l'uscita e in $2.file i file di lavoro.
raccogli_molti() {   # raccogli_molti PARALLELI USCITA [solo-avvio]
    local l="$HOME/molti-$1" r="$T/molti-$1"
    rm -rf "$l" "$r"; cp -a "$M" "$r"
    LAVORO="$l" RADICE="$r" ARCHIVIO="$r/Archivio foto" "$QUI/foto-da-pcloud.sh" anteprima > /dev/null
    [ "${3:-}" = solo-avvio ] && return 0
    local inizio=$SECONDS
    LAVORO="$l" PARALLELI="$1" "$QUI/foto-da-pcloud.sh" raccogli 2>&1 | sed "s|$r|RADICE|g; s|$l|LAVORO|g" > "$2"
    echo "  ($((SECONDS - inizio)) s con PARALLELI=$1)"
    stato_molti "$l" "$r" > "$2.file"
}
stato_molti() {   # i file di lavoro e ordinate/, coi percorsi tolti
    local l="$1" r="$2" f
    for f in impronte.tsv manifest.tsv fonti.tsv da-controllare.txt; do
        echo "-- $f"; sed "s|$r|RADICE|g; s|$l|LAVORO|g" "$l/$f"
    done
    echo "-- ordinate"; (cd "$l/ordinate" && find . -type f -exec sha256sum {} + | sort -k2)
    echo "-- tmp"; ls -A "$l/tmp" 2>/dev/null
}
raccogli_molti 1 "$T/molti-1.txt"
raccogli_molti 4 "$T/molti-4.txt"
raccogli_molti 3 "$T/molti-3.txt"
verifica "PARALLELI=1: tutti gli zip fatti"             [ "$(grep -c '^zip' "$HOME/molti-1/fonti.tsv")" -eq 13 ]
verifica "PARALLELI=1: nomi uguali con un numero"       c_e "$HOME/molti-1/ordinate/2016/07/08/IMG_3_2.jpg"
verifica "PARALLELI=4: stessa uscita di PARALLELI=1"    cmp -s "$T/molti-1.txt" "$T/molti-4.txt"
verifica "PARALLELI=4: stessi file e stessi nomi"       cmp -s "$T/molti-1.txt.file" "$T/molti-4.txt.file"
verifica "PARALLELI=3: stessi file e stessi nomi"       cmp -s "$T/molti-1.txt.file" "$T/molti-3.txt.file"
verifica "PARALLELI=4: niente doppioni in ordinate"     [ "$(sed -n '/^-- ordinate/,/^-- tmp/p' "$T/molti-4.txt.file" | grep -c '^[0-9a-f]')" -eq "$(sort -u "$HOME/molti-4/impronte.tsv" | cut -f1 | sort -u | wc -l)" ]
verifica "zip rovinato e zip con altri file segnati"    contiene "$HOME/molti-4/da-controllare.txt" "zip con 1 altri file"
# shellcheck disable=SC2016
verifica "PARALLELI=0 rifiutato"                        bash -c '! LAVORO="$1" PARALLELI=0 "$2" raccogli >/dev/null 2>&1' _ "$HOME/molti-4" "$QUI/foto-da-pcloud.sh"
cmp -s "$T/molti-1.txt.file" "$T/molti-4.txt.file" || diff "$T/molti-1.txt.file" "$T/molti-4.txt.file" | head -30
cmp -s "$T/molti-1.txt" "$T/molti-4.txt" || diff "$T/molti-1.txt" "$T/molti-4.txt" | head -30

# Ripresa: raccogli con PARALLELI=4 ucciso (kill, come pkill) a metà più volte, poi finito.
raccogli_molti 4 - solo-avvio
l="$HOME/molti-4" r="$T/molti-4" uccisi=0 restati=0
# Il primo giro si ferma tra i file sciolti, gli altri dopo 2, 5 e 9 zip in fonti.tsv.
for dopo in sciolti 2 5 9; do
    LAVORO="$l" PARALLELI=4 "$QUI/foto-da-pcloud.sh" raccogli > "$T/ucciso.txt" 2>&1 &
    pid=$!
    if [ "$dopo" = sciolti ]; then
        sleep 1
    else
        until [ "$(grep -c '^zip' "$l/fonti.tsv" 2>/dev/null)" -ge "$dopo" ] || ! kill -0 "$pid" 2>/dev/null; do sleep 0.05; done
    fi
    kill -TERM "$pid" 2>/dev/null && uccisi=$((uccisi + 1))
    wait "$pid"
    sleep 0.5
    pgrep -f "$l/tmp" > /dev/null && restati=$((restati + 1))
done
echo "  ($uccisi volte ucciso a metà; $(grep -c '^zip' "$l/fonti.tsv") zip su 13 fatti prima dell'ultimo giro)"
LAVORO="$l" PARALLELI=4 "$QUI/foto-da-pcloud.sh" raccogli > "$T/ripreso.txt" 2>&1
stato_molti "$l" "$r" > "$T/ripreso.file"
verifica "ripresa: ucciso a metà quattro volte"         [ "$uccisi" -eq 4 ]
verifica "ripresa: niente processi rimasti dopo il kill" [ "$restati" -eq 0 ]
verifica "ripresa: stessi file e nomi del giro intero"  cmp -s "$T/molti-1.txt.file" "$T/ripreso.file"
cmp -s "$T/molti-1.txt.file" "$T/ripreso.file" || diff "$T/molti-1.txt.file" "$T/ripreso.file" | head -30

echo
if [ "$KO" -gt 0 ]; then
    echo "--- uscita di foto-da-cartella.sh"; cat "$T/cartella.txt"
    echo "FALLITI $KO su $((OK + KO))"; exit 1
fi
echo "Tutto a posto: $OK controlli."
