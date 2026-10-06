# Servizio "visione" di FotoTimeline: CLIP multilingue su CPU, con l'indice dei vettori.
# Lo costruisce deploy/docker-compose.yml (contesto: questa cartella).

FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    VISIONE_MODELLI=/modelli \
    VISIONE_DATI=/dati \
    HF_HUB_DISABLE_TELEMETRY=1

WORKDIR /app

# torch solo CPU: l'immagine con CUDA peserebbe gigabyte in più per niente.
COPY requirements-modello.txt requirements.txt ./
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch==2.14.1 \
    && pip install -r requirements-modello.txt -r requirements.txt

# I pesi (circa 1,1 GB) entrano nell'immagine, in uno strato che cambia solo se cambia modello.py:
# a runtime niente download, e il container parte anche senza internet.
COPY app/__init__.py app/modello.py app/
RUN python -m app.modello && rm -rf /root/.cache
ENV HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1

COPY app/ app/

# Stesso uid dell'app; /dati è il volume dell'indice e prende proprietario da qui.
RUN useradd --uid 1000 --create-home visione && mkdir -p /dati && chown visione:visione /dati
USER visione
EXPOSE 8000

# nice: le build della CI su server2 hanno la precedenza sul calcolo dei vettori.
CMD ["nice", "-n", "10", "uvicorn", "--factory", "app.main:crea_app", "--host", "0.0.0.0", "--port", "8000", \
     "--workers", "1", "--no-access-log"]
