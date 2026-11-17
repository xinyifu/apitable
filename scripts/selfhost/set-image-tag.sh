#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <image-tag>" >&2
  exit 2
fi

image_tag="$1"

if [[ ! "${image_tag}" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
  echo "Invalid Docker image tag: ${image_tag}" >&2
  exit 2
fi

env_file=".env.ghcr-local"

if [ ! -f "${env_file}" ]; then
  echo "Missing ${env_file}" >&2
  exit 1
fi

images=(
  IMAGE_BACKEND_SERVER
  IMAGE_GATEWAY
  IMAGE_INIT_DB
  IMAGE_SELFHOST_OVERRIDES
  IMAGE_ROOM_SERVER
  IMAGE_WEB_SERVER
  IMAGE_DATABUS_SERVER
  IMAGE_IMAGEPROXY_SERVER
  IMAGE_INIT_APPDATA
)

for image_var in "${images[@]}"; do
  sed -i -E "s|^(${image_var}=apitable/[^:]+):.*$|\\1:${image_tag}|" "${env_file}"
done

echo "Updated ${env_file} image tags to ${image_tag}"
