#!/usr/bin/env python3
"""
publish_blog.py — 크론이 블로그를 초기 발행하고, 작업 로그를 남긴다.
이미 커밋+푸시 완료된 포스트(2026-08-29-mcp-acp-coral-0turn-agent-comm.md)에 대해
GitHub Pages 빌드가 완료됐는지 간단히 확인하는 용도.
"""
import os
import sys
import time
import urllib.request

POST_SLUG = "2026-08-29-mcp-acp-coral-0turn-agent-comm"
URL = f"https://kdkrkwhr.github.io/ai/2026/08/29/mcp-acp-coral-0turn-agent-comm.html"

LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cron-output")
os.makedirs(LOG_DIR, exist_ok=True)
LOG_PATH = os.path.join(LOG_DIR, "publish_2026-08-29-mcp-acp-coral.log")

def log(msg):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts}] {msg}"
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    print(line)

def main():
    log(f"블로그 발행 스크립트 시작 — 포스트: {POST_SLUG}")
    log(f"대상 URL: {URL}")

    # 1) 페이지 로드 시도 (GitHub Pages 빌드 대기 포함)
    max_tries = 3
    ok = False
    for i in range(1, max_tries + 1):
        try:
            req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=20) as r:
                code = r.getcode()
                body_preview = r.read(4096).decode("utf-8", "replace")
            if code == 200 and ("MCP" in body_preview or "0-turn" in body_preview):
                log(f"페이지 로드 성공: HTTP {code} (시도 {i}/{max_tries})")
                ok = True
                break
            else:
                log(f"페이지 로드했으나 내용 미확인: HTTP {code} (시도 {i}/{max_tries})")
        except Exception as e:
            log(f"페이지 로드 실패 (시도 {i}/{max_tries}): {e}")
        time.sleep(15)

    if not ok:
        log("경고: 페이지 로드/내용 확인에 실패 — GitHub Pages 빌드 진행 중일 수 있음")
        sys.exit(0)  # 실패해도 여기서 멈추지 않음 (cron 무음 방지용 로그만)

    log("블로그 발행 확인 완료")
    sys.exit(0)

if __name__ == "__main__":
    main()
