# 阿里云 ECS 部署手册

本目录是生产部署包，使用 Docker Compose 在同一台 ECS 上运行：

```
公网 ── HTTP :8080 ──> jqgg-app (Spring Boot + Vue 静态页)
                         ├── jqgg-mysql (MySQL 8，仅内网)
                         └── jqgg-rag   (向量服务，仅内网)
```

- 页面、接口、上传图片由 Spring Boot 单进程提供，不需要额外 Nginx。
- MySQL 与向量服务不映射公网端口，外网无法直接访问。
- 数据库数据卷：`jqgg_mysql_data`
- 上传图片数据卷：`jqgg_uploads`
- 默认模型目录：`rag-service/model-small`（bge-small-zh-v1.5 量化版，约 24MB，随部署包提供）

## 1. 服务器要求

| 项目 | 建议 |
|---|---|
| CPU / 内存 | 2 核 2GB 起（使用随包轻量向量模型）；bge-m3 需要 8GB |
| 系统盘 | 20GB 起（镜像、数据库、模型、备份） |
| 系统 | Alibaba Cloud Linux 3 / Ubuntu 22.04 / 24.04 / Debian 12 |
| 安全组 | 入方向放行 TCP 22、TCP 8080（或你设置的 `HTTP_PORT`） |

> 2GB 机器建议增加 2GB swap 作为兜底。部署包默认使用 24MB 的轻量模型，
> 索引 29 条广告牌足够；若追求更高语义精度，可在 8GB 机器上切换为 bge-m3（2.3GB）。
> 2GB 机器不要同时启用 bge-m3，否则 OOM 风险很高。

2GB 机器可执行以下命令增加 2GB swap（已有 swap 时跳过）：

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

服务器上不需要预先安装 Java、MySQL、Node.js、Python，全部由 Docker 镜像提供。

> **安全提示**：直接使用 HTTP 时，后台密码和登录令牌是明文传输的。
> 正式运营前请绑定域名并启用 HTTPS（见第 5 节），或至少把安全组入方向限制为
> 你自己的固定 IP。`deploy/.env` 含数据库密码、后台密码和模型 Key，权限必须保持 600。

## 2. 首次部署

### 2.1 上传并解压

在开发机执行（二选一）：

```bash
# 已经生成好部署包时
scp deploy/bundle/jqgg-deploy.tar.gz root@<ECS公网IP>:/opt/

# 在服务器上
cd /opt
tar -xzf jqgg-deploy.tar.gz
cd jqgg-deploy
```

从源码重新构建部署包：

```bash
bash deploy/build-local.sh
scp deploy/bundle/jqgg-deploy.tar.gz root@<ECS公网IP>:/opt/
```

### 2.2 安装 Docker

```bash
cd /opt/jqgg-deploy
sudo bash deploy/install-docker.sh
```

脚本会根据系统选择阿里云 Docker CE 镜像，并配置国内镜像加速与日志轮转。
如果服务器上已有 `/etc/docker/daemon.json`，脚本不会覆盖它；拉取镜像失败时执行
`sudo bash deploy/fetch-images.sh` 使用备用镜像源。

### 2.3 准备配置

```bash
cd /opt/jqgg-deploy
cp deploy/.env.example deploy/.env   # 部署包已带 .env 时跳过
chmod 600 deploy/.env
vi deploy/.env
```

至少修改：

```dotenv
DB_PASSWORD=<强数据库密码>
MYSQL_ROOT_PASSWORD=<强 root 密码>
ADMIN_PASSWORD=<后台管理密码>
LLM_API_KEY=<DeepSeek 或阿里百炼 Key，可留空>
HTTP_PORT=8080
BIND_IP=0.0.0.0
```

> `.env` 里有数据库密码、后台密码和模型 Key，不要提交到 Git，也不要公开分享。

### 2.4 准备向量模型

部署包已附带轻量模型，正常不需要下载。检查文件：

```bash
cd /opt/jqgg-deploy
ls -lh rag-service/model-small/onnx/model.onnx
```

文件约 24MB。若缺失，执行：

```bash
bash deploy/download-model-small.sh
```

只有切换到 bge-m3（建议 8GB 内存）时才需要：

```bash
bash deploy/download-model.sh
# 然后修改 deploy/.env：
# RAG_MODEL_DIR=model
# EMBEDDING_MODEL=bge-m3
# EMBEDDING_MAX_LENGTH=2048
```

bge-m3 约 2.3GB，必须包含：

```
rag-service/model/onnx/model.onnx
rag-service/model/onnx/model.onnx_data   # 约 2.2GB
rag-service/model/tokenizer.json
```

### 2.5 启动

```bash
cd /opt/jqgg-deploy
sudo bash deploy/deploy.sh
```

> 部署脚本需要访问 Docker，必须用 root 或已加入 docker 组的用户执行。
> 下文所有 `docker compose` 命令同理；如果当前用户不在 docker 组，命令前加 `sudo`。

脚本会校验配置、预拉取基础镜像、构建应用和向量服务、启动容器，并等待健康检查通过。
首次启动通常需要 1～2 分钟（轻量模型加载不到 1 秒）。

看到类似输出即成功：

```
jqgg-app    Up (healthy)   0.0.0.0:8080->8080/tcp
jqgg-rag    Up (healthy)   11434/tcp
jqgg-mysql  Up (healthy)   3306/tcp
```

### 2.6 开放安全组

到阿里云控制台：

`ECS 实例 → 安全组 → 配置规则 → 入方向 → 手动添加`

- 端口范围：`8080/8080`（或你设置的 `HTTP_PORT`）
- 授权对象：`0.0.0.0/0`（公开访问）或你自己的固定 IP（更安全）

浏览器访问：

- 前台：`http://<ECS公网IP>:8080/`
- 后台：`http://<ECS公网IP>:8080/admin`
- 管理密码：`deploy/.env` 中的 `ADMIN_PASSWORD`

## 3. 上线验收

1. 首页能显示 29 条示例广告牌，筛选、地图点位正常。
2. 进入后台，用 `ADMIN_PASSWORD` 登录。
3. 进入「智能服务 → 知识库」，确认索引状态为「已索引」。
4. 在前台「智能服务 → 智能选牌」问：`月租 3 万以内的可租赁广告牌`。
   - 有自然语言回答：向量与问答模型都正常。
   - 只有检索列表：`LLM_API_KEY` 未生效，到后台「API 管理」检查。
5. 上传一张图片并保存，确认前台能显示；重启后图片仍在（数据卷持久化）。
6. 重启服务器，确认容器随 Docker 自动恢复：

```bash
sudo reboot
# 重新登录后
cd /opt/jqgg-deploy
docker compose ps
```

## 4. 日常运维

```bash
cd /opt/jqgg-deploy

# 状态 / 健康检查（非 root 且未加入 docker 组时，命令前加 sudo）
docker compose ps
sudo bash deploy/healthcheck.sh

# 日志
docker compose logs -f app
docker compose logs -f rag
docker compose logs -f mysql

# 重启
docker compose restart app

# 停止（保留数据）
docker compose down

# 停止并删除数据库、上传图片（危险，无法恢复）
docker compose down -v
```

### 备份

```bash
sudo bash deploy/backup.sh
```

备份文件在 `deploy/backups/`，默认保留 14 天。建议加入 crontab：

```bash
sudo crontab -e
# 每天凌晨 3:10 备份
10 3 * * * cd /opt/jqgg-deploy && bash deploy/backup.sh >> /var/log/jqgg-backup.log 2>&1
```

### 恢复数据库

```bash
cd /opt/jqgg-deploy
docker compose stop app
gunzip -c deploy/backups/mysql-YYYYmmdd-HHMMSS.sql.gz | \
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'
docker compose start app
```

恢复上传图片：

```bash
docker run --rm -i \
  --entrypoint /bin/tar \
  -v jqgg_uploads:/data \
  jqgg-app:1.0.0 \
  -xzf - -C /data < deploy/backups/uploads-YYYYmmdd-HHMMSS.tar.gz
```

### 更新应用

在开发机重新构建并上传：

```bash
bash deploy/build-local.sh
scp deploy/bundle/jqgg-deploy.tar.gz root@<ECS公网IP>:/opt/
```

服务器上只替换应用包和启动脚本：

```bash
cd /opt/jqgg-deploy
# 解压新包后覆盖 deploy/app.jar，再执行
sudo bash deploy/update.sh
```

更新脚本会先做数据库和图片备份，再重建 app 镜像并重启。

## 5. 绑定域名与 HTTPS

如果使用中国大陆 ECS，域名解析到大陆 IP 前需要完成 ICP 备案，否则 80/443 可能被拦截。
备案完成后再做 HTTPS，推荐用 Caddy 自动申请证书：

```bash
# 以 Ubuntu 为例
sudo apt install -y caddy
```

`/etc/caddy/Caddyfile`：

```caddyfile
your-domain.com {
    encode zstd gzip
    request_body {
        max_size 60MB
    }
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
    }
}
```

```bash
sudo systemctl reload caddy
```

安全组放行 80/443。把 `.env` 中的 `BIND_IP` 改为 `127.0.0.1` 后执行
`docker compose up -d app`，8080 就只监听本机，由 Caddy 对外提供 HTTPS。

同时到腾讯位置服务控制台，把新域名加入地图 Key 的授权域名白名单，否则地图可能不显示。

## 6. 常见问题

| 现象 | 处理 |
|---|---|
| `docker pull` 超时 | `sudo bash deploy/fetch-images.sh`；或检查 `/etc/docker/daemon.json` 的镜像加速 |
| `deploy.sh` 报模型不完整 | 轻量模型执行 `bash deploy/download-model-small.sh`；bge-m3 执行 `bash deploy/download-model.sh` |
| 应用容器一直 unhealthy | `docker compose logs --tail=200 app`，常见原因是数据库密码不一致或端口被占用 |
| RAG 容器反复重启 / 被 OOM Kill | `free -h`、`docker stats`；确认使用 `RAG_MODEL_DIR=model-small`，或升级到 8GB 后再用 bge-m3 |
| 页面能开但地图空白 | 腾讯地图 Key 的域名白名单、浏览器网络、Key 是否有效 |
| 智能选牌只有列表没有回答 | 后台「API 管理」检查检索生成线路；首次启动后修改 `.env` 的 `LLM_API_KEY` 不会覆盖数据库里已初始化的 Key |
| 修改 `.env` 的后台密码不生效 | `docker compose up -d --force-recreate app` |
| 忘记后台密码 | 修改 `deploy/.env` 的 `ADMIN_PASSWORD`，然后 `docker compose up -d --force-recreate app` |
| 端口被占用 | 修改 `.env` 的 `HTTP_PORT`，`docker compose up -d app`，并同步安全组 |
| MySQL 初始化脚本没有执行 | 只有数据卷为空时才会执行 `sql/init.sql`；不要在有数据时删除数据卷 |
| 需要升级数据库结构 | 先备份，再按 README 执行 `sql/db-maintenance.sql`、`sql/contract-module.sql`、`sql/billboard-image-meta.sql`、`sql/concurrency-fixes.sql`（均幂等） |

## 7. 配置项说明

| 变量 | 说明 |
|---|---|
| `HTTP_PORT` | 公网访问端口，默认 8080 |
| `BIND_IP` | 端口监听地址，默认 `0.0.0.0`；上 HTTPS 后改为 `127.0.0.1` |
| `DB_PASSWORD` | 应用数据库账号密码，首次初始化时写入 MySQL |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码，仅容器内使用 |
| `ADMIN_PASSWORD` | 后台固定登录密码 |
| `LLM_API_KEY` | 云端问答模型 Key；留空则只做检索 |
| `LLM_BASE_URL` / `LLM_MODEL` | OpenAI 兼容接口地址与模型名 |
| `MAP_AK` | 腾讯位置服务 JS API Key |
| `JAVA_OPTS` | JVM 参数，2GB 机器默认 384MB 堆 |
| `RAG_MIN_SCORE` | 语义检索最低相似度，默认 0.5 |
| `RAG_MODEL_DIR` | 模型目录：默认 `model-small`；bge-m3 用 `model` |
| `EMBEDDING_MODEL` | 向量模型名，默认 `bge-small-zh-v1.5` |
| `EMBEDDING_MAX_LENGTH` | 最大 token 数，轻量模型 512；bge-m3 为 2048 |

> 首次启动时，应用会把环境变量中的向量、问答、地图配置写入 `api_config` 表。
> 之后以数据库中的配置为准；再改 `.env` 不会自动覆盖它，需要在后台「API 管理」修改。
