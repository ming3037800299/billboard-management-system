FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/

WORKDIR /app

# onnxruntime 需要 libgomp；同时把 Debian 源换成阿里云镜像，避免国内构建超时。
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || true \
    && apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 rag \
    && useradd --uid 10001 --gid rag --home-dir /home/rag --shell /usr/sbin/nologin rag \
    && mkdir -p /home/rag \
    && chown rag:rag /home/rag

COPY requirements.txt /app/requirements.txt

RUN pip install --no-cache-dir \
        onnxruntime==1.31.0 \
        transformers==5.20.1 \
        sentencepiece==0.2.2 \
        numpy==2.4.6

COPY --chown=rag:rag embed_server.py /app/embed_server.py

USER rag

ENV HOME=/home/rag \
    TRANSFORMERS_OFFLINE=1 \
    HF_HUB_OFFLINE=1 \
    EMBEDDING_HOST=0.0.0.0 \
    EMBEDDING_PORT=11434 \
    EMBEDDING_MAX_LENGTH=2048

EXPOSE 11434

HEALTHCHECK --interval=30s --timeout=5s --start-period=300s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:11434/health', timeout=3)"

CMD ["python", "embed_server.py"]
