#!/bin/bash
# Panda Car Launcher 签名脚本
# 使用 platform 签名对 APK 进行系统级签名

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SIGNING_DIR="$SCRIPT_DIR/signing"
APK_PATH="$1"

if [ -z "$APK_PATH" ]; then
    echo "用法: $0 <unsigned.apk>"
    exit 1
fi

if [ ! -f "$SIGNING_DIR/platform.pem" ] || [ ! -f "$SIGNING_DIR/platform.x509.pem" ]; then
    echo "错误: 签名文件不存在"
    echo "请确保 signing/platform.pem 和 signing/platform.x509.pem 存在"
    exit 1
fi

# 查找 apksigner
if [ -n "$ANDROID_HOME" ]; then
    APKSIGNER="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools/" | tail -1)/apksigner"
elif [ -n "$ANDROID_SDK_ROOT" ]; then
    APKSIGNER="$ANDROID_SDK_ROOT/build-tools/$(ls "$ANDROID_SDK_ROOT/build-tools/" | tail -1)/apksigner"
else
    APKSIGNER="apksigner"
fi

echo "使用 apksigner: $APKSIGNER"

# 签名
$APKSIGNER sign \
    --key "$SIGNING_DIR/platform.pem" \
    --cert "$SIGNING_DIR/platform.x509.pem" \
    --out "${APK_PATH%.apk}-signed.apk" \
    "$APK_PATH"

echo "签名完成: ${APK_PATH%.apk}-signed.apk"

# 验证
$APKSIGNER verify --print-certs "${APK_PATH%.apk}-signed.apk"
