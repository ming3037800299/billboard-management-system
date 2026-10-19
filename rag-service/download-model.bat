@echo off
setlocal
set BASE=https://www.modelscope.cn/models/BAAI/bge-m3/resolve/master
cd /d %~dp0

if not exist model\onnx mkdir model\onnx
if not exist model\1_Pooling mkdir model\1_Pooling

for %%f in (config.json tokenizer.json tokenizer_config.json sentencepiece.bpe.model modules.json sentence_bert_config.json) do (
  echo [download] %%f
  curl -L --retry 3 --continue-at - -o "model\%%f" "%BASE%/%%f"
)

echo [download] 1_Pooling\config.json
curl -L --retry 3 --continue-at - -o "model\1_Pooling\config.json" "%BASE%/1_Pooling/config.json"

echo [download] onnx\config.json
curl -L --retry 3 --continue-at - -o "model\onnx\config.json" "%BASE%/onnx/config.json"

echo [download] onnx\model.onnx
curl -L --retry 3 --continue-at - -o "model\onnx\model.onnx" "%BASE%/onnx/model.onnx"

echo [download] onnx\model.onnx_data (约 2.2GB，可中断后重新执行续传)
curl -L --retry 3 --continue-at - -o "model\onnx\model.onnx_data" "%BASE%/onnx/model.onnx_data"

echo.
echo 模型下载完成。运行 start-embedding.bat 启动本地向量服务。
pause
