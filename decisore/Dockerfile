# Immagine del decisore di Sol. Il modello NON è qui dentro: si scarica al primo avvio
# nel volume /modelli (3 GB), così l'immagine resta piccola e veloce da ricostruire.

# --- 1. costruzione: compila llama.cpp e prepara le "ruote" Python -------------------
FROM python:3.12-slim AS costruttore
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential cmake git \
 && rm -rf /var/lib/apt/lists/*

ARG LLAMA_CPP_PYTHON=0.3.35
# GGML_NATIVE=ON compila per la CPU di chi costruisce: l'immagine si costruisce SUL NAS
# (Intel N100: AVX2, FMA, AVX-VNNI), quindi è la scelta più veloce. Per costruire altrove
# passare --build-arg LLAMA_CMAKE_ARGS="-DGGML_NATIVE=OFF -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON".
ARG LLAMA_CMAKE_ARGS="-DGGML_NATIVE=ON -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF"
# SemIf (ex OpenJev, MIT) fissato a un commit preciso.
ARG SEMIF_COMMIT=23cf1f39fc9534fe81437200959b6dfc7106e45a

RUN CMAKE_ARGS="${LLAMA_CMAKE_ARGS}" CMAKE_BUILD_PARALLEL_LEVEL=4 \
    pip wheel --no-cache-dir --no-deps --wheel-dir /ruote "llama-cpp-python==${LLAMA_CPP_PYTHON}"
RUN pip wheel --no-cache-dir --no-deps --wheel-dir /ruote \
    "semif-phase1 @ git+https://github.com/TheoLeeCJ/SemIf-OpenJev@${SEMIF_COMMIT}"

# --- 2. immagine finale ----------------------------------------------------------------
FROM python:3.12-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /servizio
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY --from=costruttore /ruote /ruote
# --no-deps: SemIf dichiara torch tra le dipendenze, ma col backend llama.cpp non serve.
RUN pip install --no-cache-dir --no-deps /ruote/*.whl && rm -rf /ruote

COPY app ./app
COPY prova ./prova

# MALLOC_ARENA_MAX=2: ogni thread del server altrimenti si tiene una sua riserva di memoria
# (i logit copiati a ogni token sono ~1 MB l'uno) e la RAM cresce senza motivo.
ENV PYTHONUNBUFFERED=1 \
    MALLOC_ARENA_MAX=2 \
    SOL_CARTELLA_MODELLI=/modelli \
    HF_HOME=/modelli/hf \
    HF_HUB_DISABLE_TELEMETRY=1 \
    TRANSFORMERS_NO_ADVISORY_WARNINGS=1 \
    TRANSFORMERS_VERBOSITY=error

# Utente 1000:100 = stesso uid dell'utente del NAS: i file in /modelli restano suoi
# e si possono cancellare senza sudo.
RUN useradd -u 1000 -g 100 -M -d /servizio sol && mkdir -p /modelli && chown 1000:100 /modelli
USER 1000:100

EXPOSE 8300
# Controlla solo che il servizio risponda (anche "in_avvio" va bene: il download dura minuti).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl -fsS http://127.0.0.1:8300/salute || exit 1
CMD ["uvicorn", "app.servizio:app", "--host", "0.0.0.0", "--port", "8300", "--workers", "1", "--no-access-log"]
