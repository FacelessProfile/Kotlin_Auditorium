"""Fail deployment when the public updater serves a stale version or a different APK."""

import hashlib
import json
import os
import time
import urllib.parse
import urllib.request


def verify():
    endpoint = "https://academus.site/api/app/version"
    request = urllib.request.Request(endpoint, headers={"Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=30) as response:
        metadata = json.load(response)
    expected = {
        "version_code": int(os.environ["EXPECTED_VERSION_CODE"]),
        "version_name": os.environ["EXPECTED_VERSION_NAME"],
        "sha256": os.environ["EXPECTED_SHA256"],
        "file_size": int(os.environ["EXPECTED_FILE_SIZE"]),
    }
    for key, value in expected.items():
        if metadata.get(key) != value:
            raise ValueError(f"Public {key} does not match the built release")
    download = urllib.parse.urljoin(endpoint, metadata["download_url"])
    if urllib.parse.urlsplit(download)[:2] != urllib.parse.urlsplit(endpoint)[:2]:
        raise ValueError("APK download must use the production origin")
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(download, timeout=60) as response:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    if digest.hexdigest() != expected["sha256"] or size != expected["file_size"]:
        raise ValueError("Public APK does not match the signed build")
    print(f"Verified public Android release {expected['version_name']} ({size} bytes)")


if __name__ == "__main__":
    for attempt in range(6):
        try:
            verify()
            break
        except Exception:
            if attempt == 5:
                raise
            time.sleep(5)
