#!/usr/bin/env python3
"""Create a private, reusable release key outside the source tree; never print secrets."""
import os
from pathlib import Path
import secrets
import subprocess

root = Path.home() / "Library/Application Support/RenewBoard/signing"
root.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(root, 0o700)
key = root / "release.jks"
password = root / "password"
props = root / "signing.properties"
if key.exists():
    if not password.exists() or not props.exists():
        raise SystemExit("Existing signing material is incomplete; preserved without changes.")
    print("Existing release key retained.")
else:
    if password.exists():
        value = password.read_text().strip()
    else:
        value = secrets.token_urlsafe(36)
        fd = os.open(password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(value + "\n")
    java = Path(os.environ["JAVA_HOME"]) / "bin/keytool"
    subprocess.run([str(java), "-genkeypair", "-keystore", str(key), "-storetype", "JKS", "-storepass:file", str(password), "-keypass:file", str(password), "-alias", "renewboard", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000", "-dname", "CN=RenewBoard, O=RenewBoard Contributors, C=CN"], check=True)
    os.chmod(key, 0o600)
    fd = os.open(props, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(f"storeFile={key}\nstorePassword={value}\nkeyAlias=renewboard\nkeyPassword={value}\n")
    print("Release key created in the private application support directory.")
