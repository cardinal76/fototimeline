# Guida: le foto da pCloud a FotoTimeline

I passi che servono per portare le foto in FotoTimeline e tenerle aggiornate, nell'ordine in cui
si fanno. I dettagli tecnici del server sono in [DEPLOY.md](DEPLOY.md).

```
telefono ──(app pCloud)──▶ pCloud: Automatic Upload/
                                     │ server2, ogni 6 ore (pannello "Telefono")
                                     ▼
pCloud (tutto il resto) ──(script sul PC)──▶ LifetimeCloud: FotoTimeline/AAAA/MM/GG
                                     ▲               ▲
                     LifetimeCloud: telefono/ ──(importazione automatica, ogni 15 min)
```

- **pCloud** è dove stanno oggi le foto, e dove il telefono le carica (cartella `Automatic Upload`).
- **LifetimeCloud** è l'archivio di FotoTimeline: `FotoTimeline/AAAA/MM/GG/`.
- **server2** fa girare l'app (https://foto.marcocardinali.it) e rclone, che parla con i due cloud.
- **il PC** (WSL) fa una volta sola il trasloco delle foto vecchie, leggendo pCloud Drive da `P:`.

## Da sapere prima

- **Su LifetimeCloud le foto devono entrare via WebDAV** (rclone, l'app, lo script). Quelle
  caricate dal sito o dalle app di LifetimeCloud sono cifrate nel browser: via WebDAV arrivano
  cifrate (cominciano con `LCB2`) e l'app le dà come "illeggibili".
- **Lo script cancella da pCloud solo alla fine**, dopo 7 giorni, chiedendo conferma, e solo
  quello che ha copiato e controllato in tutti e due i posti.
- **Le scansioni in JPG sono foto per lo script**: le cartelle di documenti si escludono.

---

## 1. Preparare il PC (una volta)

In WSL.

**pCloud Drive in WSL**: gli alias nel `~/.bashrc`.

```bash
cat >> ~/.bashrc <<'EOF'

# Disco P: di Windows (pCloud Drive) in WSL
alias p-up='sudo mkdir -p /mnt/p && sudo mount -t drvfs P: /mnt/p && ls /mnt/p'
alias p-down='sudo umount /mnt/p'
EOF
source ~/.bashrc
```

**Programmi**:

```bash
sudo apt install -y unzip zip libimage-exiftool-perl rclone
rclone listremotes      # deve esserci lifetime: (WebDAV di LifetimeCloud)
```

**Il repository** (serve la chiave SSH del PC su GitHub: `cat ~/.ssh/id_ed25519.pub` →
GitHub → Settings → SSH and GPG keys → New SSH key; **non** rigenerare la chiave se esiste già,
è quella dei server):

```bash
ssh -T git@github.com                         # "Hi cardinal76! ..."
git config --global core.autocrlf input       # niente "a capo" di Windows negli script
mkdir -p ~/projects && cd ~/projects
git clone git@github.com:cardinal76/fototimeline.git
```

Per aggiornarlo: `cd ~/projects/fototimeline && git pull`.

---

## 2. Trasloco delle foto vecchie da pCloud (una volta, dal PC)

Script: `deploy/foto-da-pcloud.sh`. Lavora in `~/foto-da-pcloud/`, ogni fase si rilancia e
riprende da dove era arrivata. Sempre da `~/projects/fototimeline`, con `p-up` fatto.

| Fase | Cosa fa | Tocca pCloud? |
|---|---|---|
| `anteprima` | conta foto, video e zip per cartella | no |
| `scarica` | copia sul PC, con rclone e in parallelo, foto, video e zip (esclusi a parte) | no |
| `raccogli` | dalla copia: apre gli zip, legge la data di ogni foto e la mette in `AAAA/MM/GG`, senza doppioni | no |
| `carica` | li manda su LifetimeCloud `FotoTimeline/` e controlla | no |
| `archivia` | su pCloud un zip per mese in `Archivio foto/AAAA/AAAA-MM.zip`, controllato | aggiunge |
| `pulisci` | dopo 7 giorni e con `CANCELLA`: toglie gli originali copiati | **sì** |
| `stato` | a che punto è | no |

**2.1 Anteprima** e cartelle da escludere (documenti, scansioni, cartelle senza foto):

```bash
cd ~/projects/fototimeline
RADICE='/mnt/p' ARCHIVIO='/mnt/p/Archivio foto' ./deploy/foto-da-pcloud.sh anteprima
ESCLUDI='chitarra:pCloud_lost_and_found:qnap:avvocato:banche:diplomi:documenti:salute:schede sim:consulta:isr:pd:vendite:lavoro:ecampus' ./deploy/foto-da-pcloud.sh anteprima
```

`ARCHIVIO` è una cartella **nuova** (non `/mnt/p`). `Crypto Folder` e le cartelle di sistema si
saltano sempre. Le scelte restano in `~/foto-da-pcloud/impostazioni`.

**2.2 Scarica** (rclone dal remote `pcloud:`, in parallelo: molto più veloce che leggere da `P:`;
si interrompe e si rilancia):

```bash
nohup ./deploy/foto-da-pcloud.sh scarica > ~/foto-da-pcloud/scarica.log 2>&1 &
tail -f ~/foto-da-pcloud/scarica.log
```

**Raccogli**: dalla copia scaricata, mette in ordine per data (un `raccogli` lento già
avviato da `P:` si può fermare con `pkill -f "foto-da-pcloud.sh raccogli"`: quello che ha fatto resta):

```bash
nohup ./deploy/foto-da-pcloud.sh raccogli > ~/foto-da-pcloud/raccogli.log 2>&1 &
tail -f ~/foto-da-pcloud/raccogli.log        # Ctrl+C chiude solo il tail
./deploy/foto-da-pcloud.sh stato
```

Gli zip con dentro altri file e i file non letti restano su pCloud: sono in
`~/foto-da-pcloud/da-controllare.txt`.

**2.3 Carica** su LifetimeCloud, poi nell'app **Monta** e **Indicizza archivio**:

```bash
./deploy/foto-da-pcloud.sh carica
```

**2.4 Archivia** su pCloud (uno zip per mese):

```bash
./deploy/foto-da-pcloud.sh archivia
```

**2.5 Dopo 7 giorni**, guardata la timeline e gli zip in `Archivio foto`:

```bash
./deploy/foto-da-pcloud.sh pulisci           # mostra cosa toglie e chiede CANCELLA
rm -rf ~/foto-da-pcloud/ordinate ~/foto-da-pcloud/specchio   # le copie sul PC non servono più
```

---

## 3. Foto nuove dal telefono (automatico, su server2)

Il telefono carica su pCloud `Automatic Upload`; l'app, ogni 6 ore (o a richiesta), copia i file
nuovi in LifetimeCloud `telefono/`; l'importazione automatica li sposta in
`FotoTimeline/AAAA/MM/GG`; dopo 7 giorni li toglie da pCloud, solo se importati.

**3.1 Collegare pCloud a rclone (una volta).** Il login si fa sul PC, dove c'è il browser e rclone
sceglie da solo il server europeo:

Su **server2**, se c'è un remote `pcloud` rotto:

```bash
docker run --rm -it --user $(id -u):$(id -g) -e XDG_CACHE_HOME=/tmp -v ~/fototimeline/rclone:/config/rclone rclone/rclone:1.68 config delete pcloud
```

Sul **PC**: `rclone config` → `n` → nome `pcloud` → tipo `pcloud` → client_id e secret vuoti →
advanced `n` → browser **`y`** (login) → `y` → `q`. Prova e mostra la sezione:

```bash
rclone lsd "pcloud:Automatic Upload"         # le cartelle dei dispositivi
rclone config show pcloud                    # deve avere hostname = eapi.pcloud.com
```

Copia la sezione `[pcloud]` (contiene il token: non va incollata altrove) e su **server2** in
fondo a `~/fototimeline/rclone/rclone.conf` (`nano`, riga vuota, incolla, `Ctrl+O`, `Ctrl+X`). Poi:

```bash
sed -E 's/(token|pass) = .{12}.*/\1 = ***/' ~/fototimeline/rclone/rclone.conf   # [lifetime] e [pcloud], una volta
docker restart fototimeline-rclone-1
sleep 3
docker exec fototimeline-rclone-1 rclone lsd "pcloud:Automatic Upload" --config /config/rclone/rclone.conf
```

**3.2 Nell'app** (https://foto.marcocardinali.it, da admin): **Monta** (il riavvio di rclone lo
smonta) → **Telefono** → cartella `pcloud:Automatic Upload`, ogni `6` ore, togli dopo `7` giorni →
**Attiva** → **Salva** → **Sincronizza ora**.

Controllo da server2:

```bash
docker exec fototimeline-rclone-1 rclone size lifetime:telefono --config /config/rclone/rclone.conf
docker logs --since 10m fototimeline-app-1 2>&1 | grep -iE 'telefono|importazione' | tail
```

---

## 4. L'app, ogni giorno

- **Cloud smontato / Monta**: dopo ogni rilascio o riavvio il cloud è smontato; la timeline si
  vede, ma originali e importazioni vogliono il cloud montato.
- **Carica foto**: dal browser, direttamente in archivio.
- **Importa cartella**: una cartella di LifetimeCloud (caricata via WebDAV), con "sposta".
- **Indicizza archivio**: per le foto messe a mano in `FotoTimeline/AAAA/MM/GG`.
- **Telefono**: la sincronizzazione da pCloud.
- **Backup**: copia dei metadati in `FotoTimeline/.backup/` (anche da sola, una al giorno).
- **Salute**: il pallino dice se va tutto bene (verde), se c'è da guardare (giallo) o se qualcosa
  non va (rosso); il dialogo spiega cosa, voce per voce. Con il bot di Telegram (DEPLOY.md,
  "Salute e avvisi su Telegram") arriva un messaggio quando una voce diventa rossa e quando torna
  a posto: per esempio se il token di pCloud scade, o se il backup non si fa da due giorni.

Le modifiche all'app: pull request su `main`, poi il workflow **Rilascio** (Actions → Rilascio →
Run workflow) la mette su server2.

---

## Se qualcosa non va

| Messaggio | Causa | Rimedio |
|---|---|---|
| "Immagine illeggibile", il file comincia con `LCB2` | caricato dal sito/app di LifetimeCloud: cifrato | ricaricarlo via WebDAV (rclone, app, script) |
| "Montaggio non riuscito", nel log rclone `directory already mounted` | versione vecchia dell'app | rilasciare `main` |
| `Permission denied` su `/miniature/...` | volume miniature di root | rilasciare `main` (il rilascio lo sistema) |
| `bash\r: No such file or directory` | script con "a capo" di Windows | `git config --global core.autocrlf input`, poi `git rm -rq --cached . && git reset -q --hard` |
| `./deploy/foto-da-pcloud.sh: No such file or directory` | non sei nella cartella del repository | `cd ~/projects/fototimeline` |
| `Permission denied (publickey)` con git | chiave del PC non su GitHub | sezione 1, chiave su GitHub |
| pCloud `empty token found` | il token non è stato incollato | rifare 3.1 |
| pCloud `Invalid 'access_token' (2094)` | account europeo senza `hostname = eapi.pcloud.com` | rifare 3.1 dal PC |
| "ARCHIVIO non può essere la radice" | `ARCHIVIO=/mnt/p` | una cartella nuova, es. `/mnt/p/Archivio foto` |
| raccogli fermo | PC in sospensione o `P:` staccato | `p-up` e rilanciare: riprende |
