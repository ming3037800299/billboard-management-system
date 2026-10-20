# 本地 bge-m3 向量服务

为公路广告牌租赁系统的知识库提供 **BAAI/bge-m3** 向量化能力。

- 使用 ONNX Runtime 在本地 CPU 上推理，输出 **1024 维**归一化向量；
- 对外提供与 **Ollama 兼容**的接口，Java 端无需修改即可调用；
- 数据不出本机，无需 API Key；
- 每条广告牌文本约 0.05~0.2 秒完成向量化（CPU）。

> 该服务是 Ollama 的替代运行方式。如果本机已安装 Ollama，也可以直接
> `ollama pull bge-m3` + `ollama serve`，Java 端配置的默认地址同样是
> `http://127.0.0.1:11434`，两种方式二选一，不能同时占用 11434 端口。

## 1. 安装依赖

```bash
cd rag-service
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

依赖：`onnxruntime`、`transformers`、`sentencepiece`、`numpy`（不需要 PyTorch）。

## 2. 下载模型

首次使用需要下载 bge-m3 的 ONNX 版本（约 2.3GB，已加入 `.gitignore`）：

```bat
download-model.bat
```

脚本从 ModelScope 下载以下文件到 `model/`：

- `config.json`、`tokenizer.json`、`tokenizer_config.json`、`sentencepiece.bpe.model`
- `1_Pooling/config.json`、`modules.json`、`sentence_bert_config.json`
- `onnx/model.onnx` + `onnx/model.onnx_data`

下载支持断点续传；如果中断，重新执行脚本即可。

## 3. 启动服务

```bat
start-embedding.bat
```

或直接：

```bash
python embed_server.py
```

启动日志出现以下内容即成功：

```
[bge-m3] 模型加载完成，耗时 2.8s
[bge-m3] 服务启动: http://127.0.0.1:11434
```

服务默认监听 `127.0.0.1:11434`，可通过环境变量覆盖：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `EMBEDDING_HOST` | `127.0.0.1` | 监听地址；Docker 部署时设为 `0.0.0.0` |
| `EMBEDDING_PORT` | `11434` | 监听端口，需与 Java 端 `OLLAMA_BASE_URL` 一致 |
| `EMBEDDING_MODEL` | `bge-m3` | 模型名称（仅用于接口返回） |
| `EMBEDDING_MAX_LENGTH` | `2048` | 最大 token 数 |

## 4. 接口

| 方法与路径 | 说明 |
|---|---|
| `GET /api/tags` | 模型列表，供 Java 端检查服务是否就绪 |
| `POST /api/embed` | 批量向量化，入参 `{"model":"bge-m3","input":["文本1","文本2"]}` |
| `POST /v1/embeddings` | OpenAI 兼容向量化接口（可选） |
| `GET /health` | 健康检查 |

示例：

```bash
curl -X POST http://127.0.0.1:11434/api/embed \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"bge-m3\",\"input\":[\"杭州附近的可租赁广告牌\"]}"
```

## 5. 常见问题

| 现象 | 处理 |
|---|---|
| `Address already in use` | 11434 已被 Ollama 或其他进程占用，二选一运行 |
| 启动后 Java 仍报“本地向量模型不可用” | 确认本服务已启动；Java 端 `OLLAMA_BASE_URL` 默认 `http://127.0.0.1:11434` |
| 内存不足 | bge-m3 ONNX 加载约占 2.5~3GB 内存，建议至少 8GB 可用内存 |
| 首次请求较慢 | 模型在启动时加载（约 3 秒），启动完成后单条向量化约 0.1 秒 |
