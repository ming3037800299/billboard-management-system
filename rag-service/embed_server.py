#!/usr/bin/env python3
"""
bge-m3 本地向量服务（Ollama 兼容接口）。

用途：在无法安装 Ollama 的环境中，用 ONNX Runtime 直接运行 BAAI/bge-m3，
对外提供与 Ollama 相同的：
    GET  /api/tags
    POST /api/embed   {"model":"bge-m3","input":["文本1","文本2"]}
    POST /v1/embeddings（OpenAI 兼容，可选；同时支持 /embeddings 别名）

模型文件放在 ./model 目录（onnx/model.onnx + onnx/model.onnx_data）。
启动：python embed_server.py
默认监听 127.0.0.1:11434，与 Java 端 application.yml 的默认配置一致；
容器部署时通过 EMBEDDING_HOST=0.0.0.0 暴露给同一 Docker 网络内的应用容器。
"""
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

ROOT = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.environ.get("EMBEDDING_MODEL_DIR", os.path.join(ROOT, "model"))
ONNX_PATH = os.environ.get("EMBEDDING_ONNX_PATH", os.path.join(MODEL_DIR, "onnx", "model.onnx"))
MODEL_NAME = os.environ.get("EMBEDDING_MODEL", "bge-m3")
HOST = os.environ.get("EMBEDDING_HOST", "127.0.0.1")
PORT = int(os.environ.get("EMBEDDING_PORT", "11434"))
MAX_LENGTH = int(os.environ.get("EMBEDDING_MAX_LENGTH", "2048"))

print(f"[{MODEL_NAME}] 加载 tokenizer: {MODEL_DIR}", flush=True)
tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR, use_fast=True)

print(f"[{MODEL_NAME}] 加载 ONNX 模型: {ONNX_PATH}", flush=True)
started = time.time()
session = ort.InferenceSession(ONNX_PATH, providers=["CPUExecutionProvider"])
input_names = {i.name for i in session.get_inputs()}
output_names = [o.name for o in session.get_outputs()]
EMBEDDING_DIM = next(
    (int(o.shape[-1]) for o in session.get_outputs()
     if o.name in ("sentence_embedding", "token_embeddings", "last_hidden_state")
     and o.shape and isinstance(o.shape[-1], int)),
    0,
)
print(f"[{MODEL_NAME}] 模型加载完成，耗时 {time.time() - started:.1f}s", flush=True)
print(f"[{MODEL_NAME}] inputs={sorted(input_names)}", flush=True)
print(f"[{MODEL_NAME}] outputs={[(o.name, o.shape) for o in session.get_outputs()]}", flush=True)

_embed_lock = threading.Lock()


def embed(texts):
    """返回 L2 归一化后的 CLS 向量（bge-m3 的 dense embedding）。"""
    texts = [str(t) for t in texts]
    encoded = tokenizer(
        texts,
        padding=True,
        truncation=True,
        max_length=MAX_LENGTH,
        return_tensors="np",
    )
    feeds = {name: encoded[name] for name in input_names if name in encoded}
    if "token_type_ids" in input_names and "token_type_ids" not in feeds:
        feeds["token_type_ids"] = np.zeros_like(encoded["input_ids"])

    with _embed_lock:
        outputs = session.run(None, feeds)
    output_map = dict(zip(output_names, outputs))
    sentence = output_map.get("sentence_embedding")
    if sentence is not None and np.asarray(sentence).ndim == 2:
        vectors = np.asarray(sentence)
    else:
        hidden = output_map.get("token_embeddings", outputs[0])
        vectors = np.asarray(hidden)[:, 0, :]
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    vectors = vectors / np.maximum(norms, 1e-12)
    return vectors.astype(np.float32).tolist()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "BgeM3Embed/1.0"

    def _send(self, code, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/api/tags":
            self._send(200, {"models": [{
                "name": MODEL_NAME + ":latest",
                "model": MODEL_NAME,
                "size": 0,
            }]})
        elif self.path in ("/health", "/"):
            self._send(200, {
                "status": "ok",
                "model": MODEL_NAME,
                "embedding_dim": EMBEDDING_DIM,
            })
        else:
            self._send(404, {"error": "not found"})

    def _read_body(self):
        """读取请求体，兼容 chunked transfer encoding（JDK HttpClient 会使用）。"""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            chunks = []
            while True:
                line = self.rfile.readline(65536).strip()
                if not line:
                    break
                size = int(line.split(b";", 1)[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            return b"".join(chunks)
        length = int(self.headers.get("Content-Length", "0") or 0)
        return self.rfile.read(length) if length else b"{}"

    def do_POST(self):
        try:
            raw = self._read_body()
            try:
                text = raw.decode("utf-8")
            except UnicodeDecodeError:
                self._send(400, {"error": "请求体必须为 UTF-8 JSON"})
                return
            body = json.loads(text or "{}")
            if self.path in ("/api/embed", "/api/embeddings"):
                texts = body.get("input", body.get("prompt"))
                if isinstance(texts, str):
                    texts = [texts]
                if not isinstance(texts, list) or not texts:
                    self._send(400, {"error": "input 不能为空"})
                    return
                vectors = embed(texts)
                if self.path == "/api/embeddings" and len(vectors) == 1:
                    self._send(200, {"embedding": vectors[0]})
                else:
                    self._send(200, {"model": body.get("model", MODEL_NAME), "embeddings": vectors})
                return

            if self.path in ("/v1/embeddings", "/embeddings"):
                texts = body.get("input")
                if isinstance(texts, str):
                    texts = [texts]
                if not isinstance(texts, list) or not texts:
                    self._send(400, {"error": "input 不能为空"})
                    return
                vectors = embed(texts)
                self._send(200, {
                    "object": "list",
                    "model": body.get("model", MODEL_NAME),
                    "data": [
                        {"object": "embedding", "index": i, "embedding": v}
                        for i, v in enumerate(vectors)
                    ],
                    "usage": {"prompt_tokens": 0, "total_tokens": 0},
                })
                return

            self._send(404, {"error": "not found"})
        except Exception as exc:  # noqa: BLE001
            print(f"[{MODEL_NAME}] 请求失败: {exc}", flush=True)
            self._send(500, {"error": str(exc)})

    def log_message(self, fmt, *args):
        print(f"[{MODEL_NAME}] " + (fmt % args), flush=True)


if __name__ == "__main__":
    print(f"[{MODEL_NAME}] 服务启动: http://{HOST}:{PORT}", flush=True)
    ThreadingHTTPServer.daemon_threads = True
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
