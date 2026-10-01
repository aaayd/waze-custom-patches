"""Discover the newest uploaded Waze release and its ARM64 original package."""
import argparse
import json
import os
import re
from pathlib import Path
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError

from bs4 import BeautifulSoup
from curl_cffi import requests
from release_config import BUNDLE_SERIES

BASE = "https://www.apkmirror.com"
LISTING = BASE + "/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/"


def page(session, url):
    if urlparse(url).hostname != "www.apkmirror.com":
        raise ValueError("Unexpected metadata host")
    response = session.get(url, timeout=60)
    response.raise_for_status()
    return BeautifulSoup(response.text, "html.parser")


def discover(session):
    # APKMirror orders uploads newest first. Do not silently fall back to an older version.
    listing = page(session, LISTING)
    link = listing.select_one("h5.appRowTitle a[href*='-release/']")
    if link is None:
        raise ValueError("Waze release listing format changed")
    match = re.search(r"\b(\d+\.\d+\.\d+\.\d+)\b", link.get_text())
    if not match or not link["href"].startswith("/apk/waze/"):
        raise ValueError("Unrecognised Waze release")
    version = match[1]
    release_url = urljoin(BASE, link["href"])
    candidates = []
    for row in page(session, release_url).select(".table-row"):
        text = row.get_text(" ", strip=True)
        variant = row.select_one("a[href$='-android-apk-download/']")
        if variant and "arm64-v8a" in text:
            # Prefer the dedicated ARM64 variant over a universal bundle.
            candidates.append(("armeabi-v7a" in text, variant, text))
    if not candidates:
        raise ValueError(f"Latest Waze {version} has no ARM64 package yet")
    _, variant, text = sorted(candidates, key=lambda x: x[0])[0]
    code = re.search(r"\b(\d{6,10})\b", text)
    if not code:
        raise ValueError("Could not determine upstream versionCode")
    return {"version": version, "version_code": int(code[1]),
            "tag": f"waze-{version}-{code[1]}-patches-{BUNDLE_SERIES}", "release_url": release_url,
            "variant_url": urljoin(BASE, variant["href"]),
            "extension": ".apkm" if "BUNDLE" in text else ".apk"}


def release_exists(repository, tag):
    request = Request(f"https://api.github.com/repos/{repository}/releases/tags/{tag}",
                      headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                               "Accept": "application/vnd.github+json"})
    try:
        with urlopen(request, timeout=30) as response:
            release = json.load(response)
            return not release.get("draft", False)
    except HTTPError as error:
        if error.code == 404:
            return False
        raise


def download(session, metadata, destination):
    button = page(session, metadata["variant_url"]).select_one("a.downloadButton")
    if not button:
        raise ValueError("Missing variant download button")
    intermediate = urljoin(BASE, button["href"])
    link = page(session, intermediate).select_one("a#download-link")
    if not link:
        raise ValueError("Missing original package link")
    url = urljoin(BASE, link["href"])
    if urlparse(url).hostname != "www.apkmirror.com":
        raise ValueError("Unexpected download entry point")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".partial")
    response = session.get(url, headers={"Referer": intermediate}, stream=True, timeout=300)
    try:
        response.raise_for_status()
        with temporary.open("wb") as output:
            for block in response.iter_content(1024 * 1024):
                output.write(block)
    finally:
        response.close()
    with temporary.open("rb") as source:
        magic = source.read(4)
    if temporary.stat().st_size < 1_000_000 or magic != b"PK\x03\x04":
        raise ValueError("Download is not an APK/APKM archive")
    temporary.replace(destination)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["check", "download"])
    parser.add_argument("--metadata", default="build/upstream.json")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    session = requests.Session(impersonate="chrome")
    metadata_path = Path(args.metadata)
    if args.command == "check":
        metadata = discover(session)
        repository = os.environ.get("GITHUB_REPOSITORY")
        skip = bool(repository and release_exists(repository, metadata["tag"])) and not args.force
        metadata["skip"] = skip
        metadata_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")
        print(json.dumps(metadata, indent=2))
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a") as output:
                output.write(f"build={'false' if skip else 'true'}\ntag={metadata['tag']}\n")
    else:
        metadata = json.loads(metadata_path.read_text())
        path = Path("downloads") / f"waze-{metadata['version']}-original-arm64{metadata['extension']}"
        download(session, metadata, path)
        metadata["input"] = str(path)
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")
        print(f"Downloaded {path} ({path.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
