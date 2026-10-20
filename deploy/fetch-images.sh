#!/usr/bin/env bash
# 预拉取基础镜像。直连 Docker Hub 失败时自动改用国内镜像源并重新打标签。
set -Eeuo pipefail

if ! command -v docker >/dev/null 2>&1; then
    echo "未安装 Docker，请先执行：sudo bash install-docker.sh" >&2
    exit 1
fi

MIRRORS=(
    "docker.1ms.run"
    "docker.m.daocloud.io"
    "docker.xuanyuan.me"
)

pull_with_mirrors() {
    local image="$1"
    local mirror_image_base="$2"

    if docker image inspect "$image" >/dev/null 2>&1; then
        echo "[image] 已存在：$image"
        return 0
    fi

    echo "[image] 拉取：$image"
    if timeout 300 docker pull "$image"; then
        return 0
    fi

    for mirror in "${MIRRORS[@]}"; do
        local candidate="${mirror}/${mirror_image_base}"
        echo "[image] 直连失败，尝试镜像源：$candidate"
        if timeout 300 docker pull "$candidate"; then
            docker tag "$candidate" "$image"
            echo "[image] 已通过 ${mirror} 拉取并标记为 $image"
            return 0
        fi
    done

    echo "[image] 无法拉取 $image" >&2
    return 1
}

pull_with_mirrors "mysql:8.0.41" "library/mysql:8.0.41"
pull_with_mirrors "eclipse-temurin:25-jre" "library/eclipse-temurin:25-jre"
pull_with_mirrors "python:3.12-slim" "library/python:3.12-slim"

echo "[image] 基础镜像准备完成"
