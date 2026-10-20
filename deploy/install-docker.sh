#!/usr/bin/env bash
# 在阿里云 Linux / Ubuntu / Debian 上安装 Docker Engine 与 Compose 插件。
# 仅需执行一次：sudo bash install-docker.sh
set -Eeuo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "请使用 root 或 sudo 执行：sudo bash $0" >&2
    exit 1
fi

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    echo "[docker] 已安装：$(docker --version)，$(docker compose version)"
else
    . /etc/os-release

    case "${ID:-}" in
        ubuntu|debian)
            echo "[docker] 检测到 ${PRETTY_NAME}，使用阿里云镜像安装 Docker CE"
            apt-get update -y
            apt-get install -y ca-certificates curl gnupg
            install -m 0755 -d /etc/apt/keyrings
            curl -fsSL --retry 3 "https://mirrors.aliyun.com/docker-ce/linux/${ID}/gpg" \
                -o /etc/apt/keyrings/docker.asc
            chmod a+r /etc/apt/keyrings/docker.asc
            echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://mirrors.aliyun.com/docker-ce/linux/${ID} ${VERSION_CODENAME} stable" \
                > /etc/apt/sources.list.d/docker.list
            apt-get update -y
            apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
            ;;
        centos|rhel|rocky|almalinux|alinux|fedora)
            echo "[docker] 检测到 ${PRETTY_NAME}，使用阿里云镜像安装 Docker CE"
            if command -v dnf >/dev/null 2>&1; then
                PKG=dnf
            else
                PKG=yum
            fi
            ${PKG} install -y curl
            REPO_VER="${VERSION_ID%%.*}"
            case "${ID}:${REPO_VER}" in
                alinux:2|centos:7|rhel:7) REPO_VER=7 ;;
                alinux:3|centos:8|rhel:8|rocky:8|almalinux:8) REPO_VER=8 ;;
                centos:9|rhel:9|rocky:9|almalinux:9|fedora:*) REPO_VER=9 ;;
            esac
            cat > /etc/yum.repos.d/docker-ce.repo <<EOF
[docker-ce-stable]
name=Docker CE Stable
baseurl=https://mirrors.aliyun.com/docker-ce/linux/centos/${REPO_VER}/\$basearch/stable
enabled=1
gpgcheck=1
gpgkey=https://mirrors.aliyun.com/docker-ce/linux/centos/gpg
EOF
            ${PKG} install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
            ;;
        *)
            echo "[docker] 未识别的系统：${PRETTY_NAME:-unknown}" >&2
            echo "请参考 https://help.aliyun.com/zh/ecs/use-cases/install-and-use-docker 手动安装 Docker 后重试。" >&2
            exit 1
            ;;
    esac

    systemctl enable --now docker
fi

# 配置国内镜像加速与日志轮转；已有 daemon.json 时不覆盖，避免影响服务器上的其他项目。
if [ ! -f /etc/docker/daemon.json ]; then
    mkdir -p /etc/docker
    cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.m.daocloud.io"
  ],
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF
    systemctl restart docker
    echo "[docker] 已配置国内镜像加速与日志轮转"
else
    echo "[docker] 已存在 /etc/docker/daemon.json，保留现有配置"
    if ! grep -q "registry-mirrors" /etc/docker/daemon.json; then
        echo "[docker] 警告：现有配置没有 registry-mirrors；若拉取镜像失败，可执行 bash fetch-images.sh 使用备用镜像源。"
    fi
fi

docker --version
docker compose version
echo "[docker] 安装完成"
