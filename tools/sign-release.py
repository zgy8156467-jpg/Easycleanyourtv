#!/usr/bin/env python3
"""Sign a tvhome-zh release before it is uploaded.

The private key stays on the release machine. The server only ever sees the signature and the
public key, and re-checks the signature against the bytes it actually received — a package that
was signed as A and uploaded as B is rejected there, not three weeks later when every TV has
quietly stopped updating.

    # once per machine, then keep the private key off every other box
    python3 tools/sign-release.py --init

    # per release
    python3 tools/sign-release.py dist/app-modern-release.apk --channel modern --code 87 --name 2.3.0

It prints the two values the console asks for:

    sha256  9f2c...
    sig     MEUCIQ...

`--init` also prints the base64 public key to paste into Signer.PUBLIC_KEY, and writes the PEM
copy that belongs at /root/launcher-feedback/ota-pub.pem on the server.
"""
import argparse
import base64
import hashlib
import os
import re
import subprocess
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

CHANNELS = ("modern", "legacy", "beta", "idun")
DEFAULT_KEY = os.path.expanduser("~/.ssh/ota-signing-key.pem")


def canonical(channel, code, sha256, size):
    """Must match Signer.canonical() in the launcher, byte for byte."""
    return ("v2\n%s\n%d\n%s\n%d" % (channel, int(code), sha256.lower(), int(size))).encode("utf-8")


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def digest_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _sdk_dir():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return os.environ[var]
    here = os.path.dirname(os.path.abspath(__file__))
    props = os.path.join(here, os.pardir, "launcher", "local.properties")
    try:
        with open(props, encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("sdk.dir="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


def badging(path):
    """(package, versionCode, minSdk) from the APK, or None when aapt2 is not available."""
    sdk = _sdk_dir()
    if sdk:
        root = os.path.join(sdk, "build-tools")
        try:
            versions = sorted(os.listdir(root), reverse=True)
        except OSError:
            versions = []
        for version in versions:
            tool = os.path.join(root, version, "aapt2")
            if not os.path.isfile(tool):
                continue
            try:
                out = subprocess.run([tool, "dump", "badging", path],
                                     capture_output=True, text=True, check=True).stdout
            except (OSError, subprocess.CalledProcessError):
                return None
            head = re.search(r"package: name='([^']+)' versionCode='(\d+)'", out)
            minsdk = re.search(r"minSdkVersion:'(\d+)'", out)
            if head and minsdk:
                return head.group(1), int(head.group(2)), int(minsdk.group(1))
            return None
    return None


def check_channel(path, channel, code):
    """Refuse to sign a package that does not belong to the channel/code it is being uploaded as.

    A signed number that differs from the APK's real versionCode makes every TV prompt for the
    same update forever; a legacy package uploaded as modern silently strands the whole channel.
    """
    info = badging(path)
    if info is None:
        print("提示：找不到 aapt2，跳过 APK 内容校验", file=sys.stderr)
        return
    package, version_code, min_sdk = info
    want_pkg = "com.thunderstone.idun.dev" if channel == "idun" else "com.gathin.tvhome"
    if package != want_pkg:
        sys.exit("错误：%s 渠道的包名应是 %s，这个 APK 是 %s" % (channel, want_pkg, package))
    if version_code != code:
        sys.exit("错误：--code %d 与 APK 里的 versionCode %d 不一致；装完会反复提示更新"
                 % (code, version_code))
    if channel == "legacy":
        if min_sdk > 21:
            sys.exit("错误：legacy 渠道要 minSdk 14 的包，这个 APK 是 %d" % min_sdk)
    elif min_sdk < 23:
        sys.exit("错误：%s 渠道要 minSdk 23 的包，这个 APK 是 %d" % (channel, min_sdk))


def init(key_path):
    private = ec.generate_private_key(ec.SECP256R1())
    pem = private.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    os.makedirs(os.path.dirname(key_path), exist_ok=True)
    fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as fh:
        fh.write(pem)
    public_der = private.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    public_pem = private.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    pub_path = os.path.join(os.path.dirname(key_path), "ota-pub.pem")
    with open(pub_path, "wb") as fh:
        fh.write(public_pem)
    print("私钥  %s  (0600)" % key_path)
    print("公钥  %s  -> 服务器 /root/launcher-feedback/ota-pub.pem" % pub_path)
    print()
    print("把下面这串填进 Signer.PUBLIC_KEY（客户端）：")
    print()
    print(base64.b64encode(public_der).decode("ascii"))
    print()
    print("私钥丢了 = 以后的包都签不出同一个身份；私钥泄露 = 别人能给所有电视推包。")


def main():
    parser = argparse.ArgumentParser(description="签一个 tvhome-zh 发布包")
    parser.add_argument("apk", nargs="?", help="要签的 APK")
    parser.add_argument("--init", action="store_true", help="生成密钥对（只做一次）")
    parser.add_argument("--channel", choices=CHANNELS, help="modern / legacy / beta / idun")
    parser.add_argument("--code", type=int, help="versionCode，必须和 APK 里的一致")
    parser.add_argument("--name", default="", help="versionName，仅供显示，不参与签名")
    parser.add_argument("--key", default=DEFAULT_KEY, help="私钥路径")
    args = parser.parse_args()

    if args.init:
        init(args.key)
        return 0

    if not args.apk or not args.channel or args.code is None:
        parser.error("需要 APK、--channel 和 --code（或 --init）")
    if not os.path.isfile(args.apk):
        parser.error("找不到文件：%s" % args.apk)

    check_channel(args.apk, args.channel, args.code)

    try:
        with open(args.key, "rb") as fh:
            private = serialization.load_pem_private_key(fh.read(), password=None)
    except OSError:
        parser.error("读不到私钥 %s，先跑 --init" % args.key)

    size = os.path.getsize(args.apk)
    sha = digest_of(args.apk)
    message = canonical(args.channel, args.code, sha, size)
    sig = b64url(private.sign(message, ec.ECDSA(hashes.SHA256())))

    if args.name:
        print("channel %s  code %d  name %s" % (args.channel, args.code, args.name))
    else:
        print("channel %s  code %d" % (args.channel, args.code))
    print("size    %d" % size)
    print("sha256  %s" % sha)
    print("sig     %s" % sig)
    print()
    print("同一个 APK 发到两个渠道要各签一次：channel 在签名串里。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
