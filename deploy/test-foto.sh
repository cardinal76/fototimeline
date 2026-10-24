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
# 4. Cartelle doppie, con un finto LifetimeCloud che le ha: carica crea le cartelle prima di
#    copiare (una alla volta) e si ferma se ce ne sono di doppie; sistema-cartelle-doppie.sh
#    le trova, le riunisce senza perdere né cancellare niente, e rilanciato non fa niente.
# 5. metti-da-parte, con rclone (dopo scarica) e con mv: uno zip con altri file (col nome
#    lungo), un file non letto e uno zip rovinato finiscono in "Da controllare/<percorso>";
#    senza SPOSTA non si muove niente, un file già lì non si sovrascrive, uno sparito si
#    salta, il rilancio non fa niente; dopo, anteprima, scarica, raccogli e pulisci non la vedono.
# 6. pulisci con SENZA_ARCHIVIO=si: senza archivia vuole il controllo di verifica-tutto.sh
#    passato dopo carica e i GIORNI, avvisa che resta solo LifetimeCloud, chiede CANCELLA.
# 7. confronta-tutto.sh: un finto pCloud e un finto LifetimeCloud dopo un giro vero di
#    foto-da-pcloud.sh, con un finto export del DB: sciolte e negli zip trovate, mai raccolte
#    (nuove o no), raccolte non indicizzate, zip rovinato, ESCLUDI, "Da controllare", file di
#    LifetimeCloud senza scheda e schede senza file, il video ricompresso, il "probabile";
#    tutto in sola lettura, con e senza SHA-256 dal server; fermato a metà e ripreso; file (e una
#    cartella) spariti da pCloud dopo l'elenco: "sparito", il giro va avanti, mentre un errore di
#    rete ferma il passo 3.
# 8. ordina-da-controllare.sh su un finto "Da controllare": foto nuove (su pCloud e LifetimeCloud)
#    e già note, documenti per mese, doppioni tra zip, nomi uguali, __MACOSX, un non letto, uno
#    zip rovinato a metà e uno irrecuperabile; senza ORDINA niente, il rilancio non rifà niente,
#    ucciso a metà e ripreso come un giro intero, gli zip originali intatti, un giro solo alla volta.
# 9. ricomprimi-video-grandi.sh con un finto pCloud, un finto LifetimeCloud (cartelle locali) e un
#    finto mongosh (node, sopra un JSON): elenco in sola lettura, spazio insufficiente, giro completo
#    con due video veri fatti da ffmpeg, ripresa dopo un caricamento non riuscito (né riscaricato né
#    ricompresso), rilancio che non rifà niente, durata diversa che va in errore, 413 di LifetimeCloud
#    con CRF più alto, in sottofondo con stato; mai niente cancellato o spostato su pCloud.
#
# Serve: exiftool, rclone, zip, unzip, flock (ffmpeg facoltativo, per un video vero; ffmpeg con
# libx265 e node per la 9, che senza si salta).
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
    # "Creo N cartelle": carica le crea prima di copiare (dopo c34f8b9).
    grep -Ev '^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9:]+ (NOTICE|INFO|ERROR)|Transferred:|Checks:|Elapsed time|^Creo [0-9]+ cartelle in ' "$out.grezza" \
        | sed -E 's|[0-9]{2}/[0-9]{2}/[0-9]{4} [0-9]{2}:[0-9]{2}|DATA|g' \
        | sed -E 's|^(Da controllare \(restano su pCloud\): )[0-9]+ righe|\1N righe|' \
        | sed 's/, metti-da-parte, stato)$/, stato)/' > "$out"
    {
        echo "== file di lavoro"
        (cd "$l" && for f in impostazioni impronte.tsv manifest.tsv fonti.tsv da-controllare.txt archiviati.tsv radici.txt filtri-scarica.txt da-cancellare.txt; do
            # Senza i nomi d'esempio degli zip con altri file (e il loro conto
            # nell'uscita): dopo c34f8b9 non si scrivono più, con migliaia di file
            # facevano uscire raccogli (SIGPIPE). Senza RIMASTI (impostazioni e filtri di
            # scarica): c'è dalla fase metti-da-parte.
            [ -f "$f" ] && { echo "-- $f"; grep -v -e '^    ' -e '^RIMASTI=' -e '^- /Da controllare/\*\*$' "$f" || true; }
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

# --- 4. cartelle create prima della copia; cartelle doppie su LifetimeCloud ---------------
echo "4. cartelle doppie"
# Un finto LifetimeCloud sopra rclone: "finto:X" è $FINTO/X, e una cartella "11~2" è una
# seconda copia di "11" con lo stesso nome, come quelle che fa LifetimeCloud. lsf le elenca
# col nome vero (con -R rientra ogni volta nella prima copia, come rclone su WebDAV: sotto
# una cartella tripla tutto compare tre volte), un percorso porta sempre alla prima copia e
# moveto ne sposta una sola. Il resto lo fa rclone vero. In $FINTO.log ogni comando; prima
# della copia di carica (quella con --backup-dir), in $FINTO.cartelle le cartelle che la
# destinazione ha già.
cat > "$T/bin/rclone-finto" <<'FINE'
#!/usr/bin/env bash
set -uo pipefail
printf '%s\n' "$*" >> "$FINTO.log"
[[ " $* " == *" finto:"* ]] || exec rclone "$@"
vero() {
    local p="$1" c cur prima
    [[ "$p" == finto:* ]] || { printf '%s' "$p"; return; }
    p="${p#finto:}"; cur="$FINTO"
    local IFS=/
    for c in $p; do
        [ -n "$c" ] || continue
        prima="$(compgen -G "$cur/$c~*" | LC_ALL=C sort | sed -n 1p)"
        if [ -d "$cur/$c" ] || [ -z "$prima" ]; then cur="$cur/$c"; else cur="$prima"; fi
    done
    printf '%s' "$cur"
}
elenca() {   # elenca CARTELLA PREFISSO PROFONDITA
    local d nome
    for d in "$1"/*/; do
        [ -d "$d" ] || continue
        nome="${d%/}"; nome="${nome##*/}"; nome="${nome%%~*}"
        printf '%s%s/\n' "$2" "$nome"
        if [ "$3" -gt 1 ]; then elenca "$(vero "finto:${1#"$FINTO"}/$nome")" "$2$nome/" $(($3 - 1)); fi
    done
}
case "$1" in
    lsf)
        shift; r="" prof="" dove=""
        while [ $# -gt 0 ]; do
            case "$1" in -R) r=1 ;; --max-depth) prof="$2"; shift ;; --dirs-only) ;; *) dove="$1" ;; esac
            shift
        done
        d="$(vero "$dove")"
        [ -d "$d" ] || { echo "ERROR : directory not found" >&2; exit 3; }
        if [ -z "$r" ]; then prof=1; fi
        elenca "$d" "" "${prof:-99}" ;;
    moveto)
        da="$(vero "$2")" a="$(vero "$3")"
        [ -d "$da" ] && [ ! -e "$a" ] || { echo "ERROR : non si sposta" >&2; exit 1; }
        mkdir -p "$(dirname "$a")" && mv "$da" "$a" || exit 1
        echo "INFO  : $3: Server side directory move succeeded" >&2 ;;
    *)
        if [ "$1" = copy ] && [[ " $* " == *" --backup-dir "* ]]; then
            (cd "$(vero "$3")" && find . -mindepth 1 -type d | LC_ALL=C sort) > "$FINTO.cartelle"
        fi
        args=()
        for x in "$@"; do args+=("$(vero "$x")"); done
        exec rclone "${args[@]}" ;;
esac
FINE
chmod +x "$T/bin/rclone-finto"
export FINTO="$T/finto"
mkdir -p "$FINTO"
cartelle() { (cd "$1" && find . -mindepth 1 -type d | LC_ALL=C sort); }
contenuti() { (cd "$1" && find . -type f -exec cat {} \; -exec echo \; | LC_ALL=C sort); }
sha_di() { (cd "$1" && find . -type f -exec sha256sum {} + | LC_ALL=C sort -k2); }

# carica verso una destinazione che non c'è ancora: prima tutte le cartelle, poi i file.
FC=(env RCLONE="$T/bin/rclone-finto" "$QUI/foto-da-cartella.sh")
{
    DESTINAZIONE=finto:Nuova "${FC[@]}" carica
    echo "--- di nuovo"; DESTINAZIONE=finto:Nuova "${FC[@]}" carica
} > "$T/finto-carica.txt" 2>&1
N="$(cartelle "$A/ordinate" | wc -l)"
verifica "carica: \"Creo $N cartelle\" la prima volta"     contiene "$T/finto-carica.txt" "Creo $N cartelle in finto:Nuova, una alla volta"
verifica "carica: rilanciato, nessuna cartella da creare" [ "$(grep -c '^Creo ' "$T/finto-carica.txt")" -eq 1 ]
verifica "carica: le cartelle c'erano prima della copia"  [ "$(cartelle "$A/ordinate")" = "$(cat "$FINTO.cartelle")" ]
verifica "carica: cartelle con --transfers 1 --checkers 1" grep -Eq -- '^copy .* finto:Nuova --create-empty-src-dirs --transfers 1 --checkers 1$' "$FINTO.log"
verifica "carica: tutto copiato e controllato"            [ "$(sha_di "$A/ordinate")" = "$(sha_di "$FINTO/Nuova")" ]
grep -q '^Caricate e controllate' "$T/finto-carica.txt" || cat "$T/finto-carica.txt"

# Un archivio con tre cartelle doppie, come quello di Marco: 2011/02/03 due volte, 2015/11 e
# 2015/12 tre volte; in una copia di 2015/11 anche 02 è doppia, in un'altra c'è un a.jpg
# diverso da quello della prima. Più file dell'app, che non vanno toccati.
F="$FINTO/FotoTimeline"
metti() { mkdir -p "$(dirname "$F/$1")"; printf '%s' "$2" > "$F/$1"; }
metti "2015/11/01/a.jpg" a;         metti "2015/11/03/b.jpg" b
metti "2015/11~2/01/c.jpg" c;       metti "2015/11~2/02/d.jpg" d;  metti "2015/11~2/01/a.jpg" a-diversa
metti "2015/11~3/02/e.jpg" e;       metti "2015/11~3/02~2/f.jpg" f
metti "2015/12/01/g.jpg" g;         metti "2015/12~2/02/h.jpg" h;  metti "2015/12~3/03/i.jpg" i
metti "2011/02/03/j.jpg" j;         metti "2011/02/03~2/j.jpg" j;  metti "2011/02/03~2/k.jpg" k
metti "2016/01/01/l.jpg" l;         metti ".backup/fototimeline-1.json.gz" backup
prima="$(contenuti "$F")"
SD=(env RCLONE="$T/bin/rclone-finto" DESTINAZIONE=finto:FotoTimeline REGISTRO="$T/doppie.tsv" "$QUI/sistema-cartelle-doppie.sh")
DESTINAZIONE=finto:FotoTimeline "${FC[@]}" carica > "$T/finto-ferma.txt" 2>&1
verifica "carica: con cartelle doppie si ferma"            contiene "$T/finto-ferma.txt" "ci sono cartelle doppie"
verifica "carica: con cartelle doppie non copia niente"    [ "$prima" = "$(contenuti "$F")" ]
"${SD[@]}" > "$T/doppie-elenco.txt" 2>&1
verifica "elenca: le tre cartelle doppie, e solo quelle"  [ "$(grep -c ' copie$' "$T/doppie-elenco.txt")" -eq 3 ]
verifica "elenca: 2015/11 tre volte"                      contiene "$T/doppie-elenco.txt" "2015/11: 3 copie"
verifica "elenca: 2011/02/03 due volte"                   contiene "$T/doppie-elenco.txt" "2011/02/03: 2 copie"
verifica "elenca: non tocca niente"                       [ "$prima" = "$(contenuti "$F")" ]
"${SD[@]}" sistema > "$T/doppie-sistema.txt" 2>&1
verifica "sistema: finito bene"                           contiene "$T/doppie-sistema.txt" "Fatto."
verifica "sistema: nessuna copia doppia rimasta"          [ -z "$(find "$F" -name '*~*')" ]
verifica "sistema: 2015/11 riunita (con 02 doppia dentro)" [ "$(cd "$F/2015/11" && find . -type f | LC_ALL=C sort | tr '\n' ' ')" = "./01/a.jpg ./01/c.jpg ./02/d.jpg ./02/e.jpg ./02/f.jpg ./03/b.jpg " ]
verifica "sistema: a.jpg diversa non sovrascritta"        [ "$(cat "$F/2015/11/01/a.jpg")" = a ]
verifica "sistema: a.jpg diversa elencata"                contiene "$T/doppie.tsv.diversi" "/01/a.jpg"
verifica "sistema: tutto il resto in FotoTimeline"         [ "$(printf '%s\n' "$prima" | grep -vx a-diversa | sort -u)" = "$(contenuti "$F" | sort -u)" ]
verifica "sistema: niente cancellato (tutto in -doppie)"  [ "$(printf '%s\n' "$prima" | grep -v backup | grep -v '^l$')" = "$(contenuti "$FINTO/FotoTimeline-doppie")" ]
verifica "sistema: file dell'app intatti"                 c_e "$F/.backup/fototimeline-1.json.gz"
verifica "sistema: copie una alla volta"                  [ "$(grep -c -- '^copy finto:FotoTimeline-doppie/.* --transfers 1 --checkers 1$' "$FINTO.log")" -eq 10 ]
"${SD[@]}" sistema > "$T/doppie-ancora.txt" 2>&1
verifica "sistema rilanciato: niente da fare"             contiene "$T/doppie-ancora.txt" "Nessuna cartella doppia, niente da ricopiare."
DESTINAZIONE=finto:FotoTimeline "${FC[@]}" carica > "$T/finto-dopo.txt" 2>&1
verifica "carica dopo sistema: va"                        contiene "$T/finto-dopo.txt" "Caricate e controllate"
if [ "$KO" -gt 0 ]; then
    for f in finto-ferma doppie-elenco doppie-sistema doppie-ancora finto-dopo; do echo "--- $f"; cat "$T/$f.txt"; done
fi

# --- 5. metti-da-parte: i file rimasti su pCloud tutti in "Da controllare" ----------------
echo "5. metti-da-parte"
LUNGO_ZIP="Backup del telefono vecchio con dentro anche i documenti di casa"
crea_rimasti() {
    local r="$1"
    jpg "$r/Foto/buona.jpg" buona '2015:05:06 07:08:09' '2015-05-06 10:00'
    mkdir -p "$T/z" "$r/Vecchi backup" "$r/Zip"
    jpg "$T/z/z.jpg" z '2017:01:02 10:00:00' '2017-01-02 10:00'
    finto "$T/z/nota.txt" nota '2017-01-02 10:00'
    (cd "$T/z" && zip -q -X "$r/Vecchi backup/$LUNGO_ZIP.zip" z.jpg nota.txt)
    rm -rf "$T/z"
    printf 'non sono uno zip' > "$r/Vecchi backup/rotto.zip"
    printf 'neanche io' > "$r/Zip/guasto.zip"
    # Già in "Da controllare" prima di tutto: un file col nome dello zip rovinato (non va
    # sovrascritto) e una foto (anteprima, scarica e raccogli non la devono vedere).
    mkdir -p "$r/Da controllare/Vecchi backup"
    printf 'già qui' > "$r/Da controllare/Vecchi backup/rotto.zip"
    jpg "$r/Da controllare/vecchia.jpg" vecchia '2011:01:01 10:00:00' '2011-01-01 10:00'
}
for modo in con senza; do
    r="$T/rim-$modo" l="$HOME/rimasti-$modo" D="$T/rim-$modo/Da controllare" s="$QUI/foto-da-pcloud.sh"
    crea_rimasti "$r"
    export LAVORO="$l" NOME_MAX=60 NOME_CORTO=20
    {
        RADICE="$r" ARCHIVIO="$r/Archivio foto" GIORNI=0 DESTINAZIONE="$T/dest-rim-$modo" "$s" anteprima
        [ "$modo" = con ] && PCLOUD="$r" "$s" scarica
        "$s" raccogli
    } > "$T/rim-$modo-raccogli.txt" 2>&1
    verifica "$modo: zip col nome lungo, percorso vero di pCloud" contiene "$l/da-controllare.txt" "zip con 1 altri file, resta su pCloud: $r/Vecchi backup/$LUNGO_ZIP.zip"
    verifica "$modo: zip rovinato segnato"                contiene "$l/da-controllare.txt" "zip rovinato (unzip 9): $r/Zip/guasto.zip"
    verifica "$modo: raccogli salta Da controllare"       non_c_e "$l/ordinate/2011"
    # Un file non letto (raccogli li segna quando exiftool non li sposta: qui si scrive a
    # mano), uno sparito da pCloud e una riga d'esempio rientrata delle versioni vecchie.
    finto "$r/Foto/strana.jpg" strana '2016-01-01 10:00'
    printf 'non letto: %s\n    %s\nnon letto: %s\n' "$r/Foto/strana.jpg" "$r/Foto/buona.jpg" "$r/Foto/sparita.jpg" >> "$l/da-controllare.txt"
    prima="$(sha_di "$r")"
    echo no | "$s" metti-da-parte > "$T/rim-$modo-no.txt" 2>&1
    verifica "$modo: senza SPOSTA non sposta niente"      [ "$prima" = "$(sha_di "$r")" ]
    verifica "$modo: senza SPOSTA niente registro"        [ ! -s "$l/messi-da-parte.tsv" ]
    verifica "$modo: prima dice quanti e quanto pesano"   grep -Eq "^Sposto in $D 3 file \([0-9.]+[KM]?B\): 1 zip con altri file, 1 non letti, 1 zip rovinati" "$T/rim-$modo-no.txt"
    verifica "$modo: segnala il file già a destinazione"  contiene "$T/rim-$modo-no.txt" "c'è già un file con lo stesso nome"
    verifica "$modo: segnala il file sparito"             contiene "$T/rim-$modo-no.txt" "Salto 1 file che su pCloud non ci sono più"
    echo SPOSTA | "$s" metti-da-parte > "$T/rim-$modo-si.txt" 2>&1
    verifica "$modo: zip con altri file in Da controllare" c_e "$D/Vecchi backup/$LUNGO_ZIP.zip"
    verifica "$modo: zip con altri file non più al posto" non_c_e "$r/Vecchi backup/$LUNGO_ZIP.zip"
    verifica "$modo: non letto in Da controllare"         c_e "$D/Foto/strana.jpg"
    verifica "$modo: non letto non più al posto"          non_c_e "$r/Foto/strana.jpg"
    verifica "$modo: zip rovinato in Da controllare"      c_e "$D/Zip/guasto.zip"
    verifica "$modo: zip rovinato non più al posto"       non_c_e "$r/Zip/guasto.zip"
    verifica "$modo: già a destinazione, non sovrascritto" [ "$(cat "$D/Vecchi backup/rotto.zip")" = 'già qui' ]
    verifica "$modo: e l'origine resta"                   c_e "$r/Vecchi backup/rotto.zip"
    verifica "$modo: la riga rientrata non conta"         c_e "$r/Foto/buona.jpg"
    verifica "$modo: registro"                            contiene "$l/messi-da-parte.tsv" "$r/Zip/guasto.zip"$'\t'"$D/Zip/guasto.zip"$'\tspostato'
    verifica "$modo: registro, già a destinazione"        contiene "$l/messi-da-parte.tsv" "$r/Vecchi backup/rotto.zip"$'\t'"$D/Vecchi backup/rotto.zip"$'\tc\'è già'
    verifica "$modo: registro, sparito"                   contiene "$l/messi-da-parte.tsv" $'sparita.jpg\tnon c\'è più su pCloud'
    verifica "$modo: impostazioni con RIMASTI"            contiene "$l/impostazioni" "RIMASTI="
    [ "$modo" = con ] && verifica "con: con rclone"       contiene "$T/rim-con-si.txt" "Sposto con rclone su $r"
    dopo="$(sha_di "$r")"
    echo SPOSTA | "$s" metti-da-parte > "$T/rim-$modo-ancora.txt" 2>&1
    verifica "$modo: rilancio, niente da spostare"        contiene "$T/rim-$modo-ancora.txt" "Niente da spostare."
    verifica "$modo: rilancio, niente cambiato"           [ "$dopo" = "$(sha_di "$r")" ]
    da_parte="$(sha_di "$D")"
    {
        "$s" anteprima
        [ "$modo" = con ] && "$s" scarica
        "$s" raccogli
        "$s" carica
        "$s" archivia
        echo CANCELLA | "$s" pulisci
        "$s" stato
    } > "$T/rim-$modo-dopo.txt" 2>&1
    verifica "$modo: anteprima salta Da controllare"     contiene "$T/rim-$modo-dopo.txt" "Salto: Archivio foto, Da controllare, Crypto Folder"
    # shellcheck disable=SC2016
    verifica "$modo: anteprima non la conta"             bash -c '! grep -q " Da controllare$" "$1"' _ "$T/rim-$modo-dopo.txt"
    verifica "$modo: raccogli non la vede"               contiene "$T/rim-$modo-dopo.txt" "0 foto e video sciolti e 0 zip da fare"
    verifica "$modo: raccogli, niente in ordinate"       non_c_e "$l/ordinate/2011"
    [ "$modo" = con ] && verifica "con: scarica la salta" non_c_e "$l/specchio/Da controllare"
    verifica "$modo: pulisci ha pulito"                  non_c_e "$r/Foto/buona.jpg"
    verifica "$modo: pulisci non tocca Da controllare"   [ "$da_parte" = "$(sha_di "$D")" ]
    verifica "$modo: stato"                              contiene "$T/rim-$modo-dopo.txt" "Da parte:    3 file in $D"
    unset LAVORO NOME_MAX NOME_CORTO
    if [ "$KO" -gt 0 ]; then
        for f in raccogli no si ancora dopo; do echo "--- rim-$modo-$f"; cat "$T/rim-$modo-$f.txt"; done
        cat "$l/messi-da-parte.tsv"
    fi
done

# --- 6. pulisci SENZA_ARCHIVIO=si: senza archivia, se il controllo di verifica-tutto è passato --
echo "6. pulisci senza archivio"
r="$T/senza-arch" l="$HOME/senza-arch" s="$QUI/foto-da-pcloud.sh"
jpg "$r/Foto/buona.jpg" buona '2015:05:06 07:08:09' '2015-05-06 10:00'
mkdir -p "$T/z" "$r/Zip"
jpg "$T/z/z.jpg" z '2017:01:02 10:00:00' '2017-01-02 10:00'
finto "$T/z/nota.txt" nota '2017-01-02 10:00'
(cd "$T/z" && zip -q -X "$r/Zip/misto.zip" z.jpg nota.txt)
rm -rf "$T/z"
export LAVORO="$l"
{
    RADICE="$r" ARCHIVIO="$r/Archivio foto" GIORNI=0 DESTINAZIONE="$T/dest-senza" "$s" anteprima
    "$s" raccogli
    "$s" carica
    echo "--- senza archivia"; echo CANCELLA | "$s" pulisci
    echo "--- senza controllo"; echo CANCELLA | SENZA_ARCHIVIO=si "$s" pulisci
    echo "--- valore sbagliato"; echo CANCELLA | SENZA_ARCHIVIO=forse "$s" pulisci
    echo "--- controllo vecchio"; echo $(( $(cat "$l/fase-carica.ok") - 10 )) > "$l/verifica-controllo.ok"
    echo CANCELLA | SENZA_ARCHIVIO=si "$s" pulisci
    echo "--- troppo presto"; date +%s > "$l/verifica-controllo.ok"
    echo CANCELLA | SENZA_ARCHIVIO=si GIORNI=7 "$s" pulisci
    echo "--- senza CANCELLA"; echo no | SENZA_ARCHIVIO=si GIORNI=0 "$s" pulisci
} > "$T/senza-arch-prima.txt" 2>&1
o="$T/senza-arch-prima.txt"
verifica "senza archivia: si ferma e dice SENZA_ARCHIVIO" contiene "$o" "prima: $s archivia (o, senza archivio, SENZA_ARCHIVIO=si $s pulisci)"
verifica "senza controllo: si ferma"               contiene "$o" "SENZA_ARCHIVIO=si vuole prima il controllo di deploy/verifica-tutto.sh passato dopo l'ultimo carica"
verifica "valore sbagliato: si ferma"              contiene "$o" "SENZA_ARCHIVIO vale si o niente, non 'forse'"
verifica "troppo presto: aspetta GIORNI"           contiene "$o" "Troppo presto: mancano 7 giorni"
verifica "avvisa che resta solo LifetimeCloud"     contiene "$o" "SENZA ARCHIVIO: dopo, l'unica copia di queste foto e video è su LifetimeCloud ($T/dest-senza)."
verifica "fin qui non ha cancellato niente"        c_e "$r/Foto/buona.jpg"
verifica "GIORNI non si ricorda dal lancio"        contiene "$l/impostazioni" "GIORNI=0"
echo CANCELLA | SENZA_ARCHIVIO=si GIORNI=0 "$s" pulisci > "$T/senza-arch-dopo.txt" 2>&1
verifica "con CANCELLA: tolto l'originale copiato" non_c_e "$r/Foto/buona.jpg"
verifica "resta lo zip con altri file"             c_e "$r/Zip/misto.zip"
verifica "non ha creato l'archivio"                non_c_e "$r/Archivio foto"
verifica "pulisci segnato fatto"                   c_e "$l/fase-pulisci.ok"
verifica "SENZA_ARCHIVIO non finisce in impostazioni" bash -c '! grep -q SENZA "$1"' _ "$l/impostazioni"
unset LAVORO
[ "$KO" -gt 0 ] && { cat "$o" "$T/senza-arch-dopo.txt"; }

# --- 7. confronta-tutto.sh: pCloud contro FotoTimeline, per contenuto ---------------------
echo "7. confronta-tutto.sh"
# Un finto pCloud e un finto LifetimeCloud (cartelle locali, rclone vero sotto un rclone che
# segna i comandi e, con SENZA_SHA, non dà lo SHA-256 senza --download, come pCloud US) e un
# finto export del DB. Il giro di foto-da-pcloud.sh è vero: anteprima, scarica, raccogli,
# carica, metti-da-parte.
cat > "$T/bin/rclone-conf" <<'FINE'
#!/usr/bin/env bash
set -uo pipefail
printf '%s\n' "$*" >> "$CONF_LOG"
if [ "$1" = hashsum ] && [ -n "${SENZA_SHA:-}" ] && [[ " $* " != *" --download "* ]]; then
    rclone "$@" | sed -E 's/^[0-9a-f]{64}  /                                                                  /'
    exit "${PIPESTATUS[0]}"
fi
[ "$1" = copyto ] && [ -n "${LENTO:-}" ] && sleep "$LENTO"
# ROTTO: un pezzo di percorso per cui copyto fallisce come una rete che cade (il file c'è).
if [ "$1" = copyto ] && [ -n "${ROTTO:-}" ] && [[ "$2" == *"$ROTTO"* ]]; then
    echo "ERROR : $2: Failed to copy: read tcp: connection reset by peer" >&2; exit 1
fi
exec rclone "$@"
FINE
chmod +x "$T/bin/rclone-conf"
export CONF_LOG="$T/conf-rclone.log"
CT="$QUI/confronta-tutto.sh"
# Il finto export: una riga per file di LifetimeCloud (fuori dalle cartelle col punto), come
# la stampa js_db: id, hash, dimensione, giorno, video, conversione, nome, percorso; poi #fine.
finto_db() {   # finto_db DESTINAZIONE USCITA [percorsi da saltare...]
    local d="$1" out="$2" p n=0 salta
    shift 2
    salta=" $* "
    : > "$out"
    while IFS= read -r p; do
        [[ "$salta" == *" $p "* ]] && continue
        n=$((n + 1))
        printf 'id-%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$n" "$(sha256sum < "$d/$p" | cut -c1-64)" "$(stat -c %s "$d/$p")" \
            "$(printf '%s' "$p" | cut -c1-10 | tr / -)" "$(case "$p" in *.mp4) echo true ;; *) echo false ;; esac)" \
            "" "$(basename "$p")" "$p" >> "$out"
    done < <(cd "$d" && find . -type f ! -path './.*' | sed 's|^\./||' | LC_ALL=C sort)
    printf '#fine\t%s\t%s\n' "$n" "$n" >> "$out"
}
aspetta_fine() {   # aspetta_fine LAVORO: finché il giro in sottofondo non ha finito (max 3 minuti)
    local i
    for ((i = 0; i < 1800; i++)); do
        if grep -Eq '^(FATTO|.*ERRORE|.*FERMATO)' "$1/confronto.passo" 2>/dev/null && [ ! -f "$1/confronto/pid" ]; then return 0; fi
        sleep 0.1
    done
    return 1
}
r="$T/conf-p" l="$HOME/conf" dest="$T/conf-lt" s="$QUI/foto-da-pcloud.sh"
jpg "$r/Foto/a b [1].jpg" a '2015:05:06 07:08:09' '2015-05-06 10:00'
cp -p "$r/Foto/a b [1].jpg" "$r/Foto/copia di a.jpg"
jpg "$r/Foto/b.jpg" b '2012:03:04 10:00:00' '2012-03-04 10:00'
finto "$r/Video/grande.mp4" 'video di molti gigabyte' '2014-04-04 12:00'
jpg "$r/TASSE/scansione.jpg" tasse '' '2010-01-01 12:00'
mkdir -p "$T/z" "$r/Zip"
jpg "$T/z/x.jpg" x '2016:07:08 10:00:00' '2016-07-08 10:00'
jpg "$T/z/è strano'.jpg" strano '2016:07:09 10:00:00' '2016-07-09 10:00'
(cd "$T/z" && zip -q -X "$r/Zip/vacanze 2016.zip" x.jpg "è strano'.jpg")
rm -rf "$T/z"; mkdir -p "$T/z"
jpg "$T/z/z.jpg" z '2017:01:02 10:00:00' '2017-01-02 10:00'
finto "$T/z/nota.txt" nota '2017-01-02 10:00'
(cd "$T/z" && zip -q -X "$r/Zip/misto.zip" z.jpg nota.txt)
rm -rf "$T/z"
printf 'non sono uno zip' > "$r/Zip/rotto.zip"
export LAVORO="$l"
{
    RADICE="$r" ARCHIVIO="$r/Archivio foto" ESCLUDI=TASSE GIORNI=0 DESTINAZIONE="$dest" "$s" anteprima
    PCLOUD="$r" "$s" scarica
    "$s" raccogli
    "$s" carica
    # Un file non letto (scritto a mano, come nella sezione 5), poi tutti in "Da controllare".
    finto "$r/Foto/strana.jpg" strana '2016-01-01 10:00'
    printf 'non letto: %s\n' "$r/Foto/strana.jpg" >> "$l/da-controllare.txt"
    printf 'file\t%s\tno\n' "$r/Foto/strana.jpg" >> "$l/fonti.tsv"
    echo SPOSTA | "$s" metti-da-parte
} > "$T/conf-giro.txt" 2>&1
# Dopo lo scarica (qui un'ora fa): una foto nuova, una vecchia mai vista, una ritoccata.
echo $(( $(date +%s) - 3600 )) > "$l/fase-scarica.ok"
jpg "$r/Foto/nuova.jpg" nuova '' "$(date '+%Y-%m-%d %H:%M')"
jpg "$r/Foto/vecchia mai vista.jpg" vecchia '' '2011-11-11 10:00'
jpg "$r/Foto/ritoccata.jpg" ritoccata '' '2014-02-03 10:00'
# LifetimeCloud: il video grande ricompresso prima di carica (più piccolo, hash diverso), la
# versione ritoccata sotto lo stesso nome e giorno, un file non da foto-da-pcloud.sh, i file
# dell'app nelle cartelle col punto.
printf 'h265' > "$dest/2014/04/04/grande.mp4"
jpg "$dest/2014/02/03/ritoccata.jpg" ritoccata-in-ft '' '2014-02-03 10:00'
jpg "$dest/2020/01/01/estraneo.jpg" estraneo '' '2020-01-01 10:00'
mkdir -p "$dest/.backup" "$dest/.compatibili"; printf 'backup' > "$dest/.backup/fototimeline-1.json.gz"
finto_db "$dest" "$T/conf-db.tsv" 2012/03/04/b.jpg 2020/01/01/estraneo.jpg
id_grande="$(awk -F'\t' '$8 == "2014/04/04/grande.mp4" { print $1 }' "$T/conf-db.tsv")"
awk -F'\t' -v OFS='\t' '$8 == "2014/04/04/grande.mp4" { $6 = "FATTA" } { print }' "$T/conf-db.tsv" > "$T/conf-db.tmp" && mv "$T/conf-db.tmp" "$T/conf-db.tsv"
printf 'compatibile' > "$dest/.compatibili/$id_grande.mp4"
# Una scheda senza file.
sed -i '$d' "$T/conf-db.tsv"
n="$(wc -l < "$T/conf-db.tsv")"
printf 'id-perso\t%s\t5\t2019-09-09\tfalse\t\tperso.jpg\t2019/09/09/perso.jpg\n#fine\t%s\t%s\n' "$(printf perso | sha256sum | cut -c1-64)" $((n + 1)) $((n + 1)) >> "$T/conf-db.tsv"
prima_p="$(sha_di "$r")" prima_lt="$(sha_di "$dest")"
CC=(env RCLONE="$T/bin/rclone-conf" FT_DB_FILE="$T/conf-db.tsv" RICOMPRESSI_SOPRA=10 CAMPIONE=3 "$CT")
: > "$CONF_LOG"
SENZA_SHA=1 "${CC[@]}" > "$T/conf-1.txt" 2>&1
aspetta_fine "$l"; rc_fine=$?
"${CC[@]}" dove > "$T/conf-dove.txt" 2>&1
R="$l/confronto-rapporto.tsv"
riga_di() { awk -F'\t' -v p="$1" -v m="${2:-}" '$6 == p && $7 == m { print $1 "|" $2 "|" $3 }' "$R"; }
verifica "stima: senza SHA-256 dal server scarica gli sciolti" contiene "$T/conf-1.txt" "pCloud non dà lo SHA-256 lato server"
verifica "stima: dice quanto scaricherà"                   grep -Eq '^Scaricherà più o meno [0-9.]+ GB' "$T/conf-1.txt"
verifica "stima: ESCLUDI contati a parte"                  contiene "$T/conf-1.txt" "in ESCLUDI (contati, non confrontati): 1 file"
verifica "giro in sottofondo finito"                        [ "$rc_fine" -eq 0 ]
verifica "con cose non spiegate: lo dice in confronto.passo" contiene "$l/confronto.passo" "NON SPIEGATE"
verifica "dove: a che punto"                               contiene "$T/conf-dove.txt" "NON SPIEGATE"
verifica "sciolta trovata (nome con spazi e [ ])"          [ "$(riga_di "$r/Foto/a b [1].jpg")" = "pcloud|trovato|" ]
verifica "copia identica trovata"                          [ "$(riga_di "$r/Foto/copia di a.jpg")" = "pcloud|trovato|" ]
verifica "dentro lo zip trovata"                           [ "$(riga_di "$r/Zip/vacanze 2016.zip" x.jpg)" = "pcloud|trovato|" ]
verifica "dentro lo zip, nome con accento e apice"         [ "$(riga_di "$r/Zip/vacanze 2016.zip" "è strano'.jpg")" = "pcloud|trovato|" ]
verifica "Da controllare confrontata: zip misto trovato"  [ "$(riga_di "$r/Da controllare/Zip/misto.zip" z.jpg)" = "pcloud|trovato|" ]
verifica "raccolta ma non indicizzata"                     [ "$(riga_di "$r/Foto/b.jpg")" = "pcloud|mancante|raccolto-non-indicizzato" ]
verifica "mai raccolta, dopo lo scarica"                   [ "$(riga_di "$r/Foto/nuova.jpg")" = "pcloud|mancante|nuovo-dopo-scarica" ]
verifica "mai raccolta, data di prima: mai-visto"          [ "$(riga_di "$r/Foto/vecchia mai vista.jpg")" = "pcloud|mancante|mai-visto" ]
verifica "stesso nome e data: probabile"                   [ "$(riga_di "$r/Foto/ritoccata.jpg")" = "pcloud|probabile|stesso-nome-e-data" ]
verifica "video ricompresso: probabile, spiegato"          [ "$(riga_di "$r/Video/grande.mp4")" = "pcloud|probabile|ricompresso-h265" ]
verifica "zip rovinato (in Da controllare)"                [ "$(riga_di "$r/Da controllare/Zip/rotto.zip")" = "pcloud|mancante|zip-rovinato" ]
verifica "non letto, in Da controllare"                    [ "$(riga_di "$r/Da controllare/Foto/strana.jpg")" = "pcloud|mancante|da-controllare" ]
verifica "Da controllare: il motivo da da-controllare.txt" grep -q $'\tda-controllare\t.*\tnon letto$' "$R"
verifica "ESCLUDI: escluso di proposito"                   [ "$(riga_di "$r/TASSE/scansione.jpg")" = "pcloud|escluso|escluso-di-proposito" ]
verifica "LifetimeCloud: raccolta non indicizzata"         [ "$(riga_di 2012/03/04/b.jpg)" = "lifetime|non-indicizzato|da-indicizzare" ]
verifica "LifetimeCloud: file estraneo non indicizzato"    [ "$(riga_di 2020/01/01/estraneo.jpg)" = "lifetime|non-indicizzato|da-indicizzare" ]
verifica "LifetimeCloud: cartelle col punto saltate"       bash -c '! grep -q "/\.\(backup\|compatibili\)/" "$1"' _ "$R"
verifica "scheda senza file"                               [ "$(riga_di 2019/09/09/perso.jpg)" = "db|senza-file|senza-file" ]
verifica "riepilogo: quanti su pCloud"                     contiene "$l/confronto-riepilogo.txt" "Su pCloud:         11 foto e video (8 sciolti, 3 in 3 zip), 10 contenuti diversi"
verifica "riepilogo: trovati"                              contiene "$l/confronto-riepilogo.txt" "Trovati:           5"
verifica "riepilogo: video compatibile contato"            contiene "$l/confronto-riepilogo.txt" "versioni compatibili dei video: 1 (1 schede con conversione FATTA, 0 senza il file; 0 senza scheda)"
verifica "riepilogo: campione uguale alle schede"          contiene "$l/confronto-riepilogo.txt" "campione riscaricato: 3 con lo SHA-256 della scheda, 0 diversi"
verifica "riepilogo: suggerisce Indicizza archivio"        contiene "$l/confronto-riepilogo.txt" "Indicizza archivio"
verifica "rapporto: esce con 3"                            bash -c '"$@" rapporto >/dev/null 2>&1; [ $? -eq 3 ]' _ "${CC[@]}"
verifica "sola lettura: pCloud com'era"                    [ "$prima_p" = "$(sha_di "$r")" ]
verifica "sola lettura: LifetimeCloud com'era"             [ "$prima_lt" = "$(sha_di "$dest")" ]
verifica "sola lettura: solo lsf, hashsum e copyto"        [ "$(cut -d' ' -f1 "$CONF_LOG" | LC_ALL=C sort -u | tr '\n' ' ')" = "copyto hashsum lsf " ]
verifica "sola lettura: copyto solo verso tmp del PC"      bash -c '! grep "^copyto" "$1" | grep -vq " $2/confronto/tmp/"' _ "$CONF_LOG" "$l"
verifica "sola lettura: niente delete/move/purge nel codice" bash -c '! grep -Eq "rc (delete|deletefile|move|moveto|purge|rmdir|rmdirs|sync|mkdir|copy) " "$1"' _ "$CT"
verifica "senza SHA-256 dal server: sciolti scaricati"     grep -q -- '^hashsum sha256 --download --checkers 8 --files-from-raw' "$CONF_LOG"
verifica "zip tolti da tmp"                                [ -z "$(ls -A "$l/confronto/tmp" 2>/dev/null)" ]
[ "$KO" -gt 0 ] && { cat "$T/conf-giro.txt" "$T/conf-1.txt" "$l/confronto.log" "$R"; }

# Sistemato quello che non torna, rilanciato: niente si riscarica e il risultato è pulito.
# Con lo SHA-256 dal server (il rclone locale lo dà) la stima lo vede.
rm -f "$r/Foto/vecchia mai vista.jpg" "$r/Foto/ritoccata.jpg" "$dest/2014/02/03/ritoccata.jpg"
finto_db "$dest" "$T/conf-db.tsv" 2020/01/01/estraneo.jpg
: > "$CONF_LOG"
"${CC[@]}" stima > "$T/conf-2.txt" 2>&1
"${CC[@]}" da 2 >> "$T/conf-2.txt" 2>&1
aspetta_fine "$l"
verifica "rilancio: SHA-256 lato server riconosciuto"     contiene "$T/conf-2.txt" "pCloud dà lo SHA-256 lato server"
verifica "rilancio: niente da scaricare"                  contiene "$T/conf-2.txt" "Da fare: 0 sciolti (SHA-256 lato server, niente da scaricare) e 0 zip (0.0 GB)."
verifica "rilancio: nessuno zip riscaricato"              bash -c '! grep -q "^copyto" "$1"' _ "$CONF_LOG"
verifica "rilancio: tutto spiegato"                       grep -q '^FATTO .*: tutto trovato o mancante per un motivo noto' "$l/confronto.passo"
verifica "rilancio: trovata b.jpg indicizzata"            [ "$(riga_di "$r/Foto/b.jpg")" = "pcloud|trovato|" ]
verifica "rilancio: rapporto esce con 0"                  "${CC[@]}" rapporto
[ "$KO" -gt 0 ] && { cat "$T/conf-2.txt" "$l/confronto.log"; }
unset LAVORO

# Ripresa: tanti zip, scaricati piano; fermato a metà e ripreso, stesso rapporto di un giro
# intero, e gli zip finiti prima non si riscaricano.
r="$T/conf-molti"
for ((z = 1; z <= 8; z++)); do
    mkdir -p "$T/z"
    for ((i = 1; i <= 3; i++)); do veloce "$T/z/IMG_$i.jpg" "$T/vuota.jpg" "zip $z foto $i" '2019-01-01 10:00'; done
    mkdir -p "$r/Album $z"; (cd "$T/z" && zip -q -X "$r/Album $z/foto [$z].zip" IMG_1.jpg IMG_2.jpg IMG_3.jpg)
    rm -rf "$T/z"
done
mkdir -p "$T/conf-molti-lt"
printf '#fine\t0\t0\n' > "$T/conf-molti-db.tsv"
CM=(env RCLONE="$T/bin/rclone-conf" RADICE="$r" PCLOUD="$r" DESTINAZIONE="$T/conf-molti-lt" FT_DB_FILE="$T/conf-molti-db.tsv" CAMPIONE=0 ZIP_PARALLELI=2 "$CT")
LAVORO="$HOME/conf-intero" "${CM[@]}" > /dev/null 2>&1; aspetta_fine "$HOME/conf-intero"
l="$HOME/conf-ripreso"
: > "$CONF_LOG"
LAVORO="$l" LENTO=0.5 "${CM[@]}" > "$T/conf-r1.txt" 2>&1
until [ "$(cat "$l/confronto/fatti" 2>/dev/null | wc -l)" -ge 3 ] || grep -q FATTO "$l/confronto.passo" 2>/dev/null; do sleep 0.1; done
LAVORO="$l" "${CM[@]}" dove > "$T/conf-dove-r.txt" 2>&1
LAVORO="$l" "${CM[@]}" ferma > /dev/null 2>&1
aspetta_fine "$l"
sleep 0.5
fatti_prima="$(grep -c '^F' "$l/confronto/sha-zip.tsv")"
verifica "ripresa: dove dice zip fatti e quanto manca"    grep -Eq '^zip: [0-9]+ su 8 fatti, scaricati' "$T/conf-dove-r.txt"
verifica "ripresa: fermato a metà"                        contiene "$l/confronto.passo" "FERMATO"
verifica "ripresa: e dice come riprendere"               contiene "$l/confronto.passo" "da 3"
verifica "ripresa: niente processi rimasti"              bash -c '! pgrep -f "[c]onfronta-tutto.sh _zip" >/dev/null && ! pgrep -f "[c]onfronta-tutto.sh esegui" >/dev/null'
verifica "ripresa: qualche zip fatto, non tutti"          [ "$fatti_prima" -ge 3 ] && [ "$fatti_prima" -lt 8 ]
LAVORO="$l" "${CM[@]}" da 3 >> "$T/conf-r1.txt" 2>&1
aspetta_fine "$l"
verifica "ripresa: finito"                               grep -q "^FATTO" "$l/confronto.passo"
verifica "ripresa: ogni zip finito scaricato una volta"  [ "$(grep -c '^copyto' "$CONF_LOG")" -le $((8 + 2)) ]
verifica "ripresa: niente zip contato due volte"         [ "$(grep -c '^F' "$l/confronto/sha-zip.tsv")" -eq 8 ]
verifica "ripresa: stesso rapporto del giro intero"      [ "$(LC_ALL=C sort "$l/confronto-rapporto.tsv" | sed "s|$l|L|g")" = "$(LC_ALL=C sort "$HOME/conf-intero/confronto-rapporto.tsv" | sed "s|$HOME/conf-intero|L|g")" ]
verifica "ripresa: 24 foto negli zip"                    contiene "$l/confronto-riepilogo.txt" "24 foto e video (0 sciolti, 24 in 8 zip)"
[ "$KO" -gt 0 ] && { cat "$T/conf-r1.txt" "$l/confronto.log" "$T/conf-dove-r.txt"; }

# Fantasmi di pCloud: file nell'elenco del passo 1 che poi non si scaricano perché pCloud dice
# che non ci sono (directory not found), loro o la cartella che li contiene. Qui si tolgono fra
# la stima e i passi 2 e 3: diventano "sparito", il giro va avanti ed esce con 0. Un errore vero
# (la rete che cade a metà di uno zip) invece ferma il passo 3, come prima.
r="$T/conf-fant" l="$HOME/conf-fant" dest="$T/conf-fant-lt"
mkdir -p "$T/z"
veloce "$T/z/IMG_1.jpg" "$T/vuota.jpg" "fant buona 1" '2019-01-01 10:00'
mkdir -p "$r/Zip" "$r/Cartella sparita" "$r/Fantasmi"
(cd "$T/z" && zip -q -X "$r/Zip/buono.zip" IMG_1.jpg)
rm -rf "$T/z"; mkdir -p "$T/z"
veloce "$T/z/IMG_2.jpg" "$T/vuota.jpg" "fant sparita 2" '2019-01-02 10:00'
(cd "$T/z" && zip -q -X "$r/Fantasmi/favicon_package.zip" IMG_2.jpg)
cp "$r/Fantasmi/favicon_package.zip" "$r/Cartella sparita/foto-pianoterra.zip"
cp "$r/Fantasmi/favicon_package.zip" "$r/Zip/rete.zip"
rm -rf "$T/z"
veloce "$r/Foto/c.jpg" "$T/vuota.jpg" "fant sciolta c" '2019-01-03 10:00'
veloce "$r/Foto/via.jpg" "$T/vuota.jpg" "fant sciolta via" '2019-01-04 10:00'
mkdir -p "$dest/2019/01/01" "$dest/2019/01/02" "$dest/2019/01/03"
unzip -q -o "$r/Zip/buono.zip" -d "$dest/2019/01/01"
unzip -q -o "$r/Zip/rete.zip" -d "$dest/2019/01/02"
cp -p "$r/Foto/c.jpg" "$dest/2019/01/03/c.jpg"
finto_db "$dest" "$T/conf-fant-db.tsv"
CF=(env RCLONE="$T/bin/rclone-conf" RADICE="$r" PCLOUD="$r" DESTINAZIONE="$dest" FT_DB_FILE="$T/conf-fant-db.tsv" CAMPIONE=0 ZIP_PARALLELI=2 LAVORO="$l" "$CT")
"${CF[@]}" stima > "$T/conf-f1.txt" 2>&1
# Dopo l'elenco spariscono: uno zip, una cartella intera con il suo zip, uno sciolto.
rm -f "$r/Fantasmi/favicon_package.zip" "$r/Foto/via.jpg"
rm -rf "$r/Cartella sparita"
: > "$CONF_LOG"
ROTTO="rete.zip" "${CF[@]}" da 2 >> "$T/conf-f1.txt" 2>&1
aspetta_fine "$l"
verifica "fantasmi: errore vero di rete ferma il passo 3"  grep -q '^\[3/5\] zip: ERRORE' "$l/confronto.passo"
verifica "fantasmi: e dice di rilanciare da 3"            grep -q 'rilancia .*da 3' "$l/confronto.log"
verifica "fantasmi: lo zip con l'errore vero non è sparito" bash -c '! grep -q "rete.zip" "$1"' _ "$l/confronto/assenti.tsv"
verifica "fantasmi: gli altri due zip segnati"           [ "$(awk -F'\t' '$1 == "zip"' "$l/confronto/assenti.tsv" | wc -l)" -eq 2 ]
verifica "fantasmi: lo sciolto segnato al passo 2"       [ "$(awk -F'\t' '$1 == "sciolto" && $4 == "Foto/via.jpg"' "$l/confronto/assenti.tsv" | wc -l)" -eq 1 ]
verifica "fantasmi: il passo 2 lo dice"                  contiene "$l/confronto.log" "Foto/via.jpg: non più su pCloud (era nell'elenco)"
verifica "fantasmi: confermato con un lsf del percorso"  grep -q '^lsf --files-only .*/Cartella sparita/foto-pianoterra.zip$' "$CONF_LOG"
# La rete torna: da 3 riprova una volta i due spariti, rifà rete.zip e arriva in fondo con 0.
: > "$CONF_LOG"
"${CF[@]}" da 3 >> "$T/conf-f1.txt" 2>&1
aspetta_fine "$l"
RF="$l/confronto-rapporto.tsv"
verifica "fantasmi: il giro arriva in fondo"             grep -q "^FATTO .*: tutto trovato o mancante per un motivo noto (3 non più su pCloud, erano nell'elenco)" "$l/confronto.passo"
verifica "fantasmi: rapporto esce con 0"                 "${CF[@]}" rapporto
verifica "fantasmi: zip sparito nel rapporto"            [ "$(awk -F'\t' -v p="$r/Fantasmi/favicon_package.zip" '$6 == p { print $2 "|" $3 }' "$RF")" = "sparito|non-piu-su-pcloud" ]
verifica "fantasmi: zip con la cartella sparita"          [ "$(awk -F'\t' -v p="$r/Cartella sparita/foto-pianoterra.zip" '$6 == p { print $2 "|" $3 }' "$RF")" = "sparito|non-piu-su-pcloud" ]
verifica "fantasmi: sciolto sparito nel rapporto"         [ "$(awk -F'\t' -v p="$r/Foto/via.jpg" '$6 == p { print $2 "|" $3 }' "$RF")" = "sparito|non-piu-su-pcloud" ]
verifica "fantasmi: gli altri trovati"                    [ "$(awk -F'\t' '$2 == "trovato"' "$RF" | wc -l)" -eq 3 ]
verifica "fantasmi: riepilogo li conta a parte"           contiene "$l/confronto-riepilogo.txt" "Non più su pCloud: 3 (erano nell'elenco"
verifica "fantasmi: riepilogo dice come vederli"         contiene "$l/confronto-riepilogo.txt" "\$2 == \"sparito\"' $RF | cut -f6"
verifica "fantasmi: RISULTATO con il conto"               contiene "$l/confronto-riepilogo.txt" "o manca per un motivo noto (3 non più su pCloud, erano nell'elenco)."
verifica "fantasmi: riprovati una volta sola"             [ "$(grep -c '^copyto .*\(favicon_package\|foto-pianoterra\)' "$CONF_LOG")" -eq 2 ]
verifica "fantasmi: buono.zip non riscaricato"           bash -c '! grep -q "^copyto .*buono.zip" "$1"' _ "$CONF_LOG"
# Il comando del riepilogo funziona davvero.
verifica "fantasmi: il comando dà i tre percorsi"        [ "$(awk -F'\t' '$2 == "sparito"' "$RF" | cut -f6 | wc -l)" -eq 3 ]
# Rilanciato di nuovo con l'elenco vecchio: si riprovano ancora una volta e restano spariti.
"${CF[@]}" stima > "$T/conf-f2.txt" 2>&1
verifica "fantasmi: la nuova stima rifà l'elenco"         bash -c '! grep -q "Fantasmi/" "$1"' _ "$l/confronto/pcloud.tsv"
[ "$KO" -gt 0 ] && { cat "$T/conf-f1.txt" "$l/confronto.log" "$l/confronto-riepilogo.txt" "$CONF_LOG"; }

# --- 8. ordina-da-controllare: quello che è in "Da controllare", riordinato -------------
echo "8. ordina-da-controllare"
# Un finto pCloud ($T/p8, remote "$T/p8") con "Da controllare" come l'ha lasciato
# metti-da-parte, un finto LifetimeCloud ($T/lt8) con una foto già nota e una del telefono,
# e il lavoro di foto-da-pcloud.sh con impostazioni e impronte (ordinate/ non c'è più).
crea_da_controllare() {   # crea_da_controllare PCLOUD LIFETIME LAVORO_PCLOUD
    local p="$1" lt="$2" lp="$3" D="$1/Da controllare" z="$T/z8"
    rm -rf "$p" "$lt" "$lp" "$z"
    mkdir -p "$D/Backup" "$D/Copie" "$D/Zip" "$D/Foto" "$lp" "$z/1/sub" "$z/2/sub/a" "$z/3" "$z/x"
    # Già su FotoTimeline (in impronte.tsv) e una foto del telefono che nessuna impronta conosce.
    jpg "$z/1/nota.jpg" nota '2015:05:06 07:08:09' '2015-05-06 10:00'
    mkdir -p "$lt/2015/05/06" "$lt/2016/07/08"
    cp -p "$z/1/nota.jpg" "$lt/2015/05/06/nota.jpg"
    printf '%s\t%s\n' "$(sha256sum < "$z/1/nota.jpg" | cut -d' ' -f1)" 2015/05/06/nota.jpg > "$lp/impronte.tsv"
    printf 'dal telefono' > "$lt/2016/07/08/IMG_0001.jpg"
    # Zip 1: la foto nota, due nuove (una col nome di quella del telefono), un PDF, un .docx,
    # un testo, un nome lungo 250 byte, __MACOSX e la spazzatura.
    jpg "$z/1/nuova.jpg" nuova '2016:07:08 10:00:00' '2016-07-08 10:00'
    jpg "$z/1/IMG_0001.jpg" mia '2016:07:08 11:00:00' '2016-07-08 11:00'
    finto "$z/1/fattura.pdf" fattura '2019-03-04 10:00'
    finto "$z/1/lettera di Marco l'anno scorso.docx" lettera '2018-11-20 10:00'
    finto "$z/1/sub/leggimi.txt" 'primo leggimi' '2019-03-10 10:00'
    finto "$z/1/$LUNGO8.txt" 'lungo uno' '2019-03-11 10:00'
    finto "$z/1/__MACOSX/._nuova.jpg" mac '2016-07-08 10:00'
    finto "$z/1/.DS_Store" ds '2016-07-08 10:00'
    finto "$z/1/sub/Thumbs.db" db '2016-07-08 10:00'
    (cd "$z/1" && zip -q -r -X "$D/Backup/l'album di Marco (2016).zip" .)
    # Zip 2: lo stesso PDF e la stessa foto nuova (doppioni), un leggimi diverso con lo
    # stesso nome e mese, lo stesso nome lungo con dentro altro, una "foto" che non è una
    # foto, e una voce con "../" (deve restare dentro).
    cp -p "$z/1/fattura.pdf" "$z/2/sub/a/fattura.pdf"
    cp -p "$z/1/nuova.jpg" "$z/2/copia.jpg"
    finto "$z/2/sub/leggimi.txt" 'secondo leggimi' '2019-03-20 10:00'
    finto "$z/2/$LUNGO8.txt" 'lungo due' '2019-03-12 10:00'
    finto "$z/2/finta.jpg" 'non sono una foto' '2014-02-03 10:00'
    finto "$z/x/fuori.txt" fuori '2012-12-12 10:00'
    (cd "$z/2/sub" && zip -q -r -X "$D/Copie/secondo [2].zip" . ../copia.jpg ../finta.jpg "../$LUNGO8.txt" ../../x/fuori.txt)
    # Uno zip con un nome di 300 byte, che il disco non accetta (fatto con perl: qui non si
    # può creare un file così): unzip non lo apre tutto, la voce si estrae accorciata.
    perl -MIO::Compress::Zip=zip,\$ZipError -e '
        zip \"lungo" => $ARGV[0], Name => "cartella/$ARGV[1].txt", Time => 1552906800 or die $ZipError' \
        "$D/Backup/nomi lunghi.zip" "$(printf 'parola-%.0s' {1..43})"
    # Zip 3 rovinato a metà (tagliato dentro l'ultima voce): la foto si recupera, il resto no.
    jpg "$z/3/salvata.jpg" salvata '2017:01:02 10:00:00' '2017-01-02 10:00'
    head -c 300000 /dev/urandom > "$z/3/grande.bin"; touch -d '2017-01-02 10:00' "$z/3/grande.bin"
    (cd "$z/3" && zip -q -X -0 "$z/rotto.zip" salvata.jpg grande.bin)
    head -c 200000 "$z/rotto.zip" > "$D/Zip/rotto a metà.zip"
    # Zip 4 irrecuperabile; un file non letto da raccogli e uno che stavolta si legge.
    printf 'neanche io' > "$D/Zip/guasto.zip"
    finto "$D/Foto/strana.jpg" strana '2016-01-01 10:00'
    jpg "$D/Foto/rinata.jpg" rinata '' '2013-02-03 10:00'
    touch -d '2018-01-01 10:00' "$D"/*/*.zip
    rm -rf "$z"
    printf 'RADICE=%q\nPCLOUD=%q\nDESTINAZIONE=%q\nRIMASTI=%q\n' "$p" "$p" "$lt" "$D" > "$lp/impostazioni"
}
LUNGO8="$(printf 'documento-%.0s' {1..24})fine"   # 250 byte
P8="$T/p8" LT8="$T/lt8" L8="$HOME/foto-da-controllare" OR8="$T/p8/Da controllare ordinati"
crea_da_controllare "$P8" "$LT8" "$HOME/pcloud-8"
OC=(env LAVORO_PCLOUD="$HOME/pcloud-8" ALTRI_LAVORI="$HOME/pcloud-8" "$QUI/ordina-da-controllare.sh")
originali="$(sha_di "$P8/Da controllare")"
prima8="$(sha_di "$P8")"
telefono="$(sha_di "$LT8")"
"${OC[@]}" anteprima > "$T/o8-anteprima.txt" 2>&1
verifica "anteprima: zip e altri file da fare"           contiene "$T/o8-anteprima.txt" "Da fare: 5 zip e 2 altri file"
verifica "anteprima: quanto scaricare"                   grep -Eq 'da scaricare\.$' "$T/o8-anteprima.txt"
echo no | "${OC[@]}" ordina > "$T/o8-no.txt" 2>&1
verifica "senza ORDINA: non fa niente"                   contiene "$T/o8-no.txt" "Non ho fatto niente."
verifica "senza ORDINA: pCloud com'era"                  [ "$prima8" = "$(sha_di "$P8")" ]
verifica "senza ORDINA: LifetimeCloud com'era"           [ "$telefono" = "$(sha_di "$LT8")" ]
verifica "senza ORDINA: niente registro"                 [ ! -s "$L8/registro.tsv" ]
echo ORDINA | PARALLELI=2 "${OC[@]}" ordina > "$T/o8-si.txt" 2>&1
R8="$L8/registro.tsv" F8="$OR8/Foto" DOC8="$OR8/Documenti"
verifica "foto nuova in Foto/AAAA/MM/GG su pCloud"       c_e "$F8/2016/07/08/nuova.jpg"
verifica "foto nuova anche su FotoTimeline"              cmp -s "$F8/2016/07/08/nuova.jpg" "$LT8/2016/07/08/nuova.jpg"
verifica "foto già su FotoTimeline: non ricopiata"       non_c_e "$F8/2015"
verifica "foto già su FotoTimeline: contata"             contiene "$R8" $'nota.jpg\t'
verifica "foto già su FotoTimeline: nel registro"        grep -q $'\tgià su FotoTimeline$' "$R8"
verifica "nome di una foto del telefono: IMG_0001-1.jpg" cmp -s "$F8/2016/07/08/IMG_0001-1.jpg" "$LT8/2016/07/08/IMG_0001-1.jpg"
verifica "la foto del telefono non sovrascritta"         [ "$(cat "$LT8/2016/07/08/IMG_0001.jpg")" = 'dal telefono' ]
verifica "su FotoTimeline solo le foto nuove"            [ "$(find "$LT8" -type f | wc -l)" -eq 6 ]
verifica "PDF nel mese della sua data"                   c_e "$DOC8/2019/03/fattura.pdf"
verifica ".docx nel mese della sua data"                 c_e "$DOC8/2018/11/lettera di Marco l'anno scorso.docx"
verifica "lo stesso PDF in due zip: una volta"           [ "$(find "$OR8" -name 'fattura*' | wc -l)" -eq 1 ]
verifica "lo stesso PDF: doppione nel registro"          grep -q $'\ta/fattura.pdf\t[0-9a-f]*\t.*/fattura.pdf\tdoppione$' "$R8"
verifica "stessa foto in due zip: una volta"             [ "$(find "$OR8" -name 'copia*' | wc -l)" -eq 0 ]
verifica "stesso nome, contenuto diverso: leggimi-1.txt" [ "$(cat "$DOC8/2019/03/leggimi.txt")-$(cat "$DOC8/2019/03/leggimi-1.txt")" = 'finto primo leggimi-finto secondo leggimi' ]
verifica "nome lungo: com'è"                             c_e "$DOC8/2019/03/$LUNGO8.txt"
verifica "nome lungo con un numero: entro 255 byte"      [ "$(find "$DOC8/2019/03" -name 'documento-*-1.txt' -printf '%f' | LC_ALL=C wc -c)" -le 255 ]
verifica "nome lungo con un numero: c'è"                 [ "$(find "$DOC8/2019/03" -name 'documento-*-1.txt' | wc -l)" -eq 1 ]
verifica "nome di 300 byte: accorciato a 255"            [ "$(find "$DOC8/2019/03" -name 'parola-*' -printf '%f' | LC_ALL=C wc -c)" -eq 255 ]
verifica "nome di 300 byte: col suo contenuto"           [ "$(cat "$DOC8/2019/03"/parola-* 2>/dev/null)" = lungo ]
verifica "voce con ../: dentro Documenti"                c_e "$DOC8/2012/12/fuori.txt"
verifica "__MACOSX e spazzatura saltati"                 [ -z "$(find "$OR8" \( -name '._*' -o -name .DS_Store -o -name Thumbs.db -o -name __MACOSX \))" ]
verifica "foto finta in uno zip: Non letti"              c_e "$OR8/Non letti/2014/02/finta.jpg"
verifica "file non letto: Non letti/AAAA/MM"             c_e "$OR8/Non letti/2016/01/strana.jpg"
verifica "non letto che ora si legge: tra le foto"       cmp -s "$F8/2013/02/03/rinata.jpg" "$LT8/2013/02/03/rinata.jpg"
verifica "zip rovinato: la foto recuperata"              cmp -s "$F8/2017/01/02/salvata.jpg" "$LT8/2017/01/02/salvata.jpg"
verifica "zip rovinato: il resto irrecuperabile"         grep -q $'rotto a metà.zip\tgrande.bin\t\t\tirrecuperabile$' "$R8"
verifica "zip rovinato: niente file a metà"              [ -z "$(find "$OR8" -name 'grande*')" ]
verifica "zip irrecuperabile nel registro"               grep -q $'guasto.zip\t\t\t\tirrecuperabile$' "$R8"
verifica "impronte delle foto nuove"                     [ "$(cut -f2 "$L8/impronte.tsv" | sort | tr '\n' ' ')" = "2013/02/03/rinata.jpg 2016/07/08/IMG_0001-1.jpg 2016/07/08/nuova.jpg 2017/01/02/salvata.jpg " ]
verifica "registro: ogni file con il suo zip"            grep -q "^$P8/Da controllare/Backup/l'album di Marco (2016).zip"$'\t'"lettera di Marco l'anno scorso.docx"$'\t' "$R8"
verifica "ricorda Indicizza archivio"                    contiene "$T/o8-si.txt" "Indicizza archivio"
verifica "zip originali intatti"                         [ "$originali" = "$(sha_di "$P8/Da controllare")" ]
verifica "su pCloud solo Da controllare ordinati in più" [ "$(cd "$P8" && find . -mindepth 1 -maxdepth 1 | sort | tr '\n' ' ')" = "./Da controllare ./Da controllare ordinati " ]
verifica "niente lasciato sul PC: tmp"                   non_c_e "$L8/tmp"
verifica "niente lasciato sul PC: in attesa"             non_c_e "$L8/in-attesa"
"${OC[@]}" stato > "$T/o8-stato.txt" 2>&1
verifica "stato: tutti fatti"                            contiene "$T/o8-stato.txt" "Fatti:       7 file su 7"
verifica "stato: il conto dei documenti"                 grep -Eq '^ +8 documento$' "$T/o8-stato.txt"
prima_p="$(sha_di "$P8")" prima_l="$(sha_di "$LT8")" prima_r="$(cat "$R8")"
echo ORDINA | "${OC[@]}" ordina > "$T/o8-ancora.txt" 2>&1
verifica "rilancio: niente da fare"                      contiene "$T/o8-ancora.txt" "Da fare: niente."
# Un giro solo alla volta: con il lucchetto preso da un altro, ordina e carica si fermano.
( flock -n 9 && echo ORDINA | "${OC[@]}" ordina > "$T/o8-doppio.txt" 2>&1; "${OC[@]}" carica > "$T/o8-doppio-carica.txt" 2>&1 ) 9>"$L8/in-corso.lock"
verifica "due giri insieme: il secondo si ferma"         contiene "$T/o8-doppio.txt" "c'è già un giro in corso"
verifica "due giri insieme: anche carica"                contiene "$T/o8-doppio-carica.txt" "c'è già un giro in corso"
"${OC[@]}" stato > "$T/o8-doppio-stato.txt" 2>&1
verifica "stato si legge anche durante un giro"          contiene "$T/o8-doppio-stato.txt" "Fatti:       7 file su 7"
verifica "rilancio: pCloud com'era"                      [ "$prima_p" = "$(sha_di "$P8")" ]
verifica "rilancio: LifetimeCloud com'era"               [ "$prima_l" = "$(sha_di "$LT8")" ]
verifica "rilancio: registro com'era"                    [ "$prima_r" = "$(cat "$R8")" ]
if [ "$KO" -gt 0 ]; then
    for f in anteprima no si stato ancora; do echo "--- o8-$f"; cat "$T/o8-$f.txt"; done
    echo "--- registro"; cat "$R8"; echo "--- ordinati"; (cd "$OR8" && find . -type f | sort)
fi

# Ripresa: 30 zip, lotti da 3, ucciso a metà (anche col lotto in attesa) e rilanciato:
# stessi file, stessi nomi e stesso registro di un giro intero.
crea_molti_da_controllare() {   # crea_molti_da_controllare PCLOUD LIFETIME LAVORO_PCLOUD
    local p="$1" lt="$2" lp="$3" D="$1/Da controllare" k z="$T/z8m"
    rm -rf "$p" "$lt" "$lp"
    mkdir -p "$D" "$lt" "$lp"
    : > "$lp/impronte.tsv"
    for ((k = 1; k <= 30; k++)); do
        rm -rf "$z"; mkdir -p "$z"
        jpg "$z/IMG_$((k % 4)).jpg" "m$k" "2016:07:0$((k % 3 + 1)) 10:00:00" '2016-07-08 10:00'
        finto "$z/conto.pdf" "conto $((k % 7))" "2019-0$((k % 5 + 1))-04 10:00"
        finto "$z/nota $k.txt" "nota $k" '2019-03-04 10:00'
        (cd "$z" && zip -q -X "$D/zip $k.zip" ./*)
    done
    rm -rf "$z"
    printf 'RADICE=%q\nPCLOUD=%q\nDESTINAZIONE=%q\nRIMASTI=%q\n' "$p" "$p" "$lt" "$D" > "$lp/impostazioni"
}
giro_molti() {   # giro_molti NOME: lavoro, pCloud e LifetimeCloud con lo stesso NOME
    local n="$1"
    crea_molti_da_controllare "$T/$n-p" "$T/$n-lt" "$HOME/$n-pc"
    MOLTI=(env LAVORO="$HOME/$n-oc" LAVORO_PCLOUD="$HOME/$n-pc" ALTRI_LAVORI="$HOME/$n-pc" LOTTO=3 PARALLELI=3 "$QUI/ordina-da-controllare.sh")
}
stato_molti8() {   # i file messi su pCloud e LifetimeCloud e il registro, coi percorsi tolti
    echo "-- ordinati"; sha_di "$T/$1-p/Da controllare ordinati"
    echo "-- lifetime"; sha_di "$T/$1-lt"
    echo "-- registro"; sed "s|$T/$1-p|P|g; s|$T/$1-lt|L|g" "$HOME/$1-oc/registro.tsv"
    echo "-- impronte"; cat "$HOME/$1-oc/impronte.tsv"
}
giro_molti intero
echo ORDINA | "${MOLTI[@]}" ordina > "$T/o8-intero.txt" 2>&1
stato_molti8 intero > "$T/o8-intero.file"
giro_molti ripreso
l="$HOME/ripreso-oc" uccisi=0 in_attesa=0
for dopo in 4 13 22; do
    echo ORDINA | "${MOLTI[@]}" ordina > "$T/o8-ucciso.txt" 2>&1 &
    pid=$!
    until [ "$(cat "$l/fatti.tsv" "$l/in-attesa/fatti.tsv" 2>/dev/null | wc -l)" -ge "$dopo" ] || ! kill -0 "$pid" 2>/dev/null; do sleep 0.05; done
    [ -d "$l/in-attesa" ] && in_attesa=$((in_attesa + 1))
    kill -TERM "$pid" 2>/dev/null && uccisi=$((uccisi + 1))
    wait "$pid"
done
echo "  ($uccisi volte ucciso a metà, $in_attesa col lotto in attesa)"
echo ORDINA | "${MOLTI[@]}" ordina > "$T/o8-ripreso.txt" 2>&1
stato_molti8 ripreso > "$T/o8-ripreso.file"
verifica "ripresa: ucciso a metà tre volte"              [ "$uccisi" -eq 3 ]
verifica "ripresa: giro intero, 30 zip fatti"            [ "$(wc -l < "$HOME/intero-oc/fatti.tsv")" -eq 30 ]
verifica "ripresa: nomi uguali con un numero"            c_e "$T/intero-p/Da controllare ordinati/Foto/2016/07/02/IMG_1-1.jpg"
verifica "ripresa: stessi file, nomi e registro"         cmp -s "$T/o8-intero.file" "$T/o8-ripreso.file"
cmp -s "$T/o8-intero.file" "$T/o8-ripreso.file" || diff "$T/o8-intero.file" "$T/o8-ripreso.file" | head -30
verifica "ripresa: niente lasciato sul PC"               non_c_e "$l/in-attesa"

# --- 9. ricomprimi-video-grandi.sh: i video troppo grandi, ricompressi sul PC ----------------
echo "9. ricomprimi-video-grandi.sh"
if ! command -v ffmpeg >/dev/null || ! command -v ffprobe >/dev/null || ! ffmpeg -hide_banner -encoders 2>/dev/null | grep libx265 >/dev/null; then
    echo "  (saltata: serve ffmpeg con libx265)"
elif ! command -v node >/dev/null; then
    echo "  (saltata: serve node per il finto mongosh)"
else
RV="$QUI/ricomprimi-video-grandi.sh"
P9="$T/v9-pcloud" LT9="$T/v9-lifetime" L9="$HOME/video-grandi" DB9="$T/v9-db.json"
CAMERA="$P9/Automatic Upload/Xiaomi 25080RABDG/DCIM/Camera"
mkdir -p "$CAMERA" "$LT9/telefono"
# Due video veri, senza perdite (grandi, si comprimono bene), con la data di ripresa.
ffmpeg -v error -f lavfi -i testsrc2=size=640x480:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 \
    -t 4 -c:v libx264 -qp 0 -preset ultrafast -c:a aac -metadata creation_time=2026-03-01T18:55:06Z \
    "$CAMERA/20260301_195506_293.mp4" < /dev/null
ffmpeg -v error -f lavfi -i mandelbrot=size=320x240:rate=25 -f lavfi -i sine=frequency=880:sample_rate=48000 \
    -t 3 -c:v libx264 -qp 0 -preset ultrafast -c:a aac -metadata creation_time=2026-03-01T17:26:45Z \
    "$CAMERA/20260301_182645_998.mp4" < /dev/null
D1="$(stat -c %s "$CAMERA/20260301_195506_293.mp4")" D2="$(stat -c %s "$CAMERA/20260301_182645_998.mp4")"
ID1=650000000000000000000001 ID2=650000000000000000000002
# La coda come la lascia il server con dove=pc: due da ricomprimere, uno fatto, uno in errore.
coda9() {   # coda9 [solo il primo]
    node -e '
const [d1, d2, solo] = process.argv.slice(1);
const v = (id, stato, nome, dim) => ({_id: id, sorgente: "pcloud:Automatic Upload", percorso: "Xiaomi 25080RABDG/DCIM/Camera/" + nome,
  dimensione: Number(dim), stato, cartella: "telefono", creatoIl: "2026-03-02T08:00:00Z"});
const docs = [v("650000000000000000000001", "DA_RICOMPRIMERE_PC", "20260301_195506_293.mp4", d1)];
if (!solo) docs.push(v("650000000000000000000002", "DA_RICOMPRIMERE_PC", "20260301_182645_998.mp4", d2),
  v("650000000000000000000003", "FATTO", "VID_3.mp4", 99), v("650000000000000000000004", "ERRORE", "VID_4.mp4", 98));
console.log(JSON.stringify(docs));' "$D1" "$D2" "${1:-}" > "$DB9"
}
# Il finto mongosh: lo script del PC gira con un db che legge e scrive il JSON. Segna ogni script.
cat > "$T/bin/finto-mongosh" <<'FINE'
#!/usr/bin/env node
const fs = require("fs"), vm = require("vm");
const file = process.env.FINTO_DB, docs = JSON.parse(fs.readFileSync(file, "utf8"));
fs.appendFileSync(process.env.FINTO_DB_LOG, process.argv[2] + "\n----\n");
const val = (x) => (x && x.toHexString ? x.toHexString() : x);
const ok = (d, q) => Object.entries(q).every(([k, c]) => c && typeof c === "object" && !c.toHexString
  ? ("$in" in c ? c.$in.map(val).includes(val(d[k])) : false) : val(d[k]) === val(c));
const collezione = {
  find: (q) => { const r = docs.filter((d) => ok(d, q || {})); const cur = {sort: () => cur, forEach: (f) => r.forEach(f)}; return cur; },
  updateOne: (q, u) => {
    const d = docs.find((x) => ok(x, q));
    if (!d) return {matchedCount: 0, modifiedCount: 0};
    for (const [k, x] of Object.entries(u.$set)) d[k] = x instanceof Date ? x.toISOString() : x;
    fs.writeFileSync(file, JSON.stringify(docs, null, 1));
    return {matchedCount: 1, modifiedCount: 1};
  }};
const db = {getSiblingDB: () => db, getCollection: () => collezione};
vm.runInNewContext(process.argv[2], {db, print: (...a) => console.log(a.join(" ")), Date, String,
  ObjectId: (s) => ({toHexString: () => s, toString: () => s}), NumberLong: (s) => Number(s), NumberInt: (n) => Number(n)});
FINE
# rclone che segna i comandi; FALLISCI_CARICA (un file): il primo caricamento su LifetimeCloud non
# va; RIFIUTA_OLTRE (byte): LifetimeCloud rifiuta i file più grandi col 413 di nginx.
cat > "$T/bin/rclone-v9" <<'FINE'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$V9_LOG"
if [ "$1" = copyto ] && [[ "$3" == "$LT9"* ]]; then
    if [ -n "${FALLISCI_CARICA:-}" ] && [ -f "$FALLISCI_CARICA" ]; then rm -f "$FALLISCI_CARICA"; echo "Failed to copy: connection reset by peer" >&2; exit 1; fi
    if [ -n "${RIFIUTA_OLTRE:-}" ] && [ "$(stat -c %s "$2")" -gt "$RIFIUTA_OLTRE" ]; then
        echo "ERROR : Failed to copy: 413 Request Entity Too Large" >&2; exit 1
    fi
fi
exec rclone "$@"
FINE
# ffmpeg che segna le ricompressioni; con CORTO taglia il risultato a 1 secondo (durata diversa).
cat > "$T/bin/ffmpeg-v9" <<'FINE'
#!/usr/bin/env bash
[[ " $* " == *" libx265 "* ]] && printf '%s\n' "$*" >> "$V9_FF"
if [ -n "${CORTO:-}" ] && [[ " $* " == *" libx265 "* ]]; then exec ffmpeg "${@:1:$#-1}" -t 1 "${@: -1}"; fi
exec ffmpeg "$@"
FINE
chmod +x "$T/bin/finto-mongosh" "$T/bin/rclone-v9" "$T/bin/ffmpeg-v9"
export V9_LOG="$T/v9-rclone.log" V9_FF="$T/v9-ffmpeg.log" FINTO_DB="$DB9" FINTO_DB_LOG="$T/v9-mongosh.log" LT9
: > "$V9_LOG"; : > "$V9_FF"; : > "$FINTO_DB_LOG"
RV9=(env FT_MONGOSH="$T/bin/finto-mongosh" RCLONE="$T/bin/rclone-v9" FFMPEG="$T/bin/ffmpeg-v9" PCLOUD="$P9" LIFETIME="$LT9" OGNI=0.2 "$RV")
campo9() { node -e 'const d = JSON.parse(require("fs").readFileSync(process.argv[1])).find((x) => x._id === process.argv[2]); console.log(d[process.argv[3]] ?? "")' "$DB9" "$1" "$2"; }
sha_p9() { (cd "$P9" && find . -type f -printf '%P %s %T@\n' | LC_ALL=C sort; find . -type f -exec sha256sum {} + | LC_ALL=C sort); }
coda9
prima_p9="$(sha_p9)"

"${RV9[@]}" elenco > "$T/v9-elenco.txt" 2>&1
verifica "elenco: i due da fare"                         contiene "$T/v9-elenco.txt" "2 da ricomprimere sul PC"
verifica "elenco: col percorso su pCloud"                contiene "$T/v9-elenco.txt" "pcloud:Automatic Upload/Xiaomi 25080RABDG/DCIM/Camera/20260301_195506_293.mp4"
verifica "elenco: anche fatti ed errori"                 grep -q '^ERRORE ' "$T/v9-elenco.txt"
verifica "elenco: in sola lettura"                       bash -c '! grep -q updateOne "$1"' _ "$FINTO_DB_LOG"

# Spazio insufficiente: non scarica niente, non cambia stato, dice quanto serve.
SPAZIO_LIBERO=1000 "${RV9[@]}" ricomprimi > "$T/v9-spazio.txt" 2>&1
verifica "spazio: esce con errore"                       [ $? -ne 0 ]
verifica "spazio: lo dice"                               contiene "$T/v9-spazio.txt" "spazio insufficiente in $L9"
verifica "spazio: niente scaricato"                      bash -c '! grep -q "^copyto $1" "$2"' _ "$P9" "$V9_LOG"
verifica "spazio: ancora da ricomprimere"                [ "$(campo9 $ID1 stato)" = DA_RICOMPRIMERE_PC ]
verifica "spazio: la pagina lo sa"                       grep -q "spazio insufficiente" <<< "$(campo9 $ID1 sulPc)"

# Il primo caricamento su LifetimeCloud non va: il primo video resta a metà, il secondo va.
: > "$T/v9-fallisci"
FALLISCI_CARICA="$T/v9-fallisci" "${RV9[@]}" ricomprimi > "$T/v9-primo.txt" 2>&1
verifica "a metà: esce con errore"                       [ $? -ne 0 ]
verifica "a metà: ricompresso tenuto per il rilancio"    c_e "$L9/$ID1/ricompresso.ok"
verifica "a metà: primo ancora da ricomprimere"          [ "$(campo9 $ID1 stato)" = DA_RICOMPRIMERE_PC ]
verifica "a metà: il secondo consegnato"                 [ "$(campo9 $ID2 stato)" = RICOMPRESSO_PC ]
verifica "a metà: due ricompressioni"                    [ "$(wc -l < "$V9_FF")" -eq 2 ]
verifica "ffmpeg: il comando del server"                 grep -q -- "-map 0 -map_metadata 0 -c copy -c:v libx265 -crf 24 -preset fast -x265-params log-level=error -tag:v hvc1 -movflags +use_metadata_tags" "$V9_FF"
verifica "ffmpeg: con -progress, senza -fs"              bash -c 'grep -q -- "-progress" "$1" && ! grep -q -- " -fs " "$1"' _ "$V9_FF"
# Ripresa: né riscaricato né ricompresso, solo caricato e segnato.
"${RV9[@]}" ricomprimi > "$T/v9-ripresa.txt" 2>&1
verifica "ripresa: finisce bene"                         [ $? -eq 0 ]
verifica "ripresa: non ricomprime"                       [ "$(wc -l < "$V9_FF")" -eq 2 ]
verifica "ripresa: non riscarica"                        [ "$(grep -c "^copyto $P9/Automatic Upload/Xiaomi 25080RABDG/DCIM/Camera/20260301_195506_293.mp4 " "$V9_LOG")" -eq 1 ]
verifica "ripresa: lo dice"                              contiene "$T/v9-ripresa.txt" "già ricompresso in un giro di prima"
for id in $ID1 $ID2; do
    f="$LT9/video-grandi-dal-pc/$id.mp4"
    verifica "giro: $id consegnato al server"            [ "$(campo9 $id stato)" = RICOMPRESSO_PC ]
    verifica "giro: $id in LifetimeCloud, dal PC"        c_e "$f"
    verifica "giro: $id SHA-256 di quel file"            [ "$(campo9 $id hash)" = "$(sha256sum < "$f" | cut -c1-64)" ]
    verifica "giro: $id dimensione di quel file"         [ "$(campo9 $id dimensioneRicompresso)" = "$(stat -c %s "$f")" ]
    verifica "giro: $id CRF 24"                          [ "$(campo9 $id crf)" = 24 ]
    verifica "giro: $id HEVC"                            [ "$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 "$f")" = hevc ]
    verifica "giro: $id riga della pagina"               grep -q "ricompresso, .* → " <<< "$(campo9 $id sulPc)"
    verifica "giro: $id niente lasciato sul PC"          non_c_e "$L9/$id"
done
verifica "giro: stessa durata"                           awk -v a="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$LT9/video-grandi-dal-pc/$ID1.mp4")" 'BEGIN { exit !(a > 3.5 && a < 4.5) }'
verifica "giro: stessa data di ripresa"                  [ "$(ffprobe -v error -show_entries format_tags=creation_time -of csv=p=0 "$LT9/video-grandi-dal-pc/$ID1.mp4")" = 2026-03-01T18:55:06.000000Z ]
verifica "giro: più piccolo"                             [ "$(stat -c %s "$LT9/video-grandi-dal-pc/$ID1.mp4")" -lt "$D1" ]
verifica "giro: niente nella cartella automatica"        [ -z "$(ls -A "$LT9/telefono")" ]
verifica "giro: fatto ed errore non toccati"             [ "$(campo9 650000000000000000000003 stato)/$(campo9 650000000000000000000004 stato)" = FATTO/ERRORE ]
verifica "registro: due fatti"                           [ "$(grep -c $'\tfatto\t' "$L9/registro.tsv")" -eq 2 ]
"${RV9[@]}" stato > "$T/v9-stato.txt" 2>&1
verifica "stato: finito"                                 contiene "$T/v9-stato.txt" "Non sta girando."
verifica "stato: il registro"                            contiene "$T/v9-stato.txt" "20260301_182645_998.mp4"
# Rilancio: niente da fare, niente cambiato.
prima_db9="$(cat "$DB9")"
"${RV9[@]}" ricomprimi > "$T/v9-ancora.txt" 2>&1
verifica "rilancio: niente da fare"                      contiene "$T/v9-ancora.txt" "Da fare: niente"
verifica "rilancio: DB com'era"                          [ "$prima_db9" = "$(cat "$DB9")" ]
verifica "rilancio: nessuna ricompressione"              [ "$(wc -l < "$V9_FF")" -eq 2 ]

# Durata diversa: errore vero, niente caricato, sul server ERRORE col motivo (si rifà con Riprova).
rm -rf "$LT9/video-grandi-dal-pc"; coda9 solo
CORTO=si "${RV9[@]}" ricomprimi > "$T/v9-corto.txt" 2>&1
verifica "durata: esce con errore"                       [ $? -ne 0 ]
verifica "durata: in errore sul server"                  [ "$(campo9 $ID1 stato)" = ERRORE ]
verifica "durata: col motivo"                            grep -q "sul PC: la durata del ricompresso" <<< "$(campo9 $ID1 messaggio)"
verifica "durata: niente caricato"                       non_c_e "$LT9/video-grandi-dal-pc/$ID1.mp4"
verifica "durata: nel registro"                          [ "$(grep -c $'\terrore\t' "$L9/registro.tsv")" -eq 1 ]
rm -rf "${L9:?}/$ID1"

# 413 di LifetimeCloud: il ricompresso col CRF 24 è rifiutato, si rifà col 28 sotto quello rifiutato.
coda9 solo
TIENI=si CRF_MASSIMO=24 "${RV9[@]}" ricomprimi > /dev/null 2>&1
d24="$(stat -c %s "$L9/$ID1/ricompresso.mp4" 2>/dev/null || echo 0)"
rm -rf "${L9:?}/$ID1" "$LT9/video-grandi-dal-pc"; coda9 solo
RIFIUTA_OLTRE=$((d24 - 1)) "${RV9[@]}" ricomprimi > "$T/v9-413.txt" 2>&1
verifica "413: finisce bene"                             [ $? -eq 0 ]
verifica "413: lo dice"                                  contiene "$T/v9-413.txt" "LifetimeCloud lo rifiuta: riprovo più compresso"
verifica "413: col CRF 28"                               [ "$(campo9 $ID1 crf)" = 28 ]
verifica "413: sotto quello rifiutato"                   [ "$(campo9 $ID1 dimensioneRicompresso)" -lt "$d24" ]
verifica "413: consegnato"                               [ "$(campo9 $ID1 stato)" = RICOMPRESSO_PC ]

# In sottofondo con nohup, seguito con stato.
rm -rf "$LT9/video-grandi-dal-pc"; coda9 solo
"${RV9[@]}" avvia > "$T/v9-avvia.txt" 2>&1
for ((i = 0; i < 600; i++)); do [ -f "$L9/pid" ] && kill -0 "$(cat "$L9/pid")" 2>/dev/null || break; sleep 0.1; done
"${RV9[@]}" stato > "$T/v9-stato2.txt" 2>&1
verifica "sottofondo: partito"                           contiene "$T/v9-avvia.txt" "Partito in sottofondo."
verifica "sottofondo: finito"                            contiene "$T/v9-stato2.txt" "Finito "
verifica "sottofondo: consegnato"                        [ "$(campo9 $ID1 stato)" = RICOMPRESSO_PC ]
verifica "sottofondo: il registro"                       contiene "$L9/ricomprimi.log" "Da fare: 1 video"

# Su pCloud niente cancellato né spostato, mai: stessi file, stesse date, stesso contenuto.
verifica "pCloud com'era"                                [ "$prima_p9" = "$(sha_p9)" ]
verifica "pCloud: solo letture"                          bash -c '! grep -E "^(delete|deletefile|move|moveto|purge|rmdir|rmdirs|sync) .*$1" "$2"' _ "$P9" "$V9_LOG"
if [ "$KO" -gt 0 ]; then
    for f in elenco spazio primo ripresa corto 413 stato2; do echo "--- v9-$f"; cat "$T/v9-$f.txt"; done
    echo "--- db"; cat "$DB9"
fi
fi

echo
if [ "$KO" -gt 0 ]; then
    echo "--- uscita di foto-da-cartella.sh"; cat "$T/cartella.txt"
    echo "FALLITI $KO su $((OK + KO))"; exit 1
fi
echo "Tutto a posto: $OK controlli."
