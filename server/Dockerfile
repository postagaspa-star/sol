# Sul NAS: un volume su /data (database) e uno su /apk (file da scaricare).
FROM python:3.14-slim

WORKDIR /srv/sol

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Le cartelle dei volumi devono esistere prima del mount: un volume vergine parte senza errori.
RUN mkdir -p /data /apk

ENV SOL_DB=/data/sol.db \
    SOL_APK_DIR=/apk \
    SOL_ENV=prod

EXPOSE 8000

# python invece di curl: l'immagine slim non ha curl.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/api/salute', timeout=4).status == 200 else 1)"]

CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
