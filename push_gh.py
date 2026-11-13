import base64
import json
import os
import sys
import urllib.error
import urllib.request

TOKEN = os.environ["GH_TOKEN"]
OWNER = "TeaPieyyds"
REPO = "EarthOnlineDirect"
ROOT = "/data/user/0/com.ai.assistance.operit/files/workspace/867d6ccc-ef55-4665-bcbf-49708250cd56"

SKIP_DIRS = {".git", ".gradle", "build", ".kotlin", ".backup", ".operit", ".idea"}
SKIP_FILES = {"local.properties"}


def api(method, path, payload=None):
    url = "https://api.github.com" + path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "token " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "Operit")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        print("HTTP %s on %s %s: %s" % (e.code, method, path, e.read().decode()[:400]))
        raise


def collect():
    out = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn in SKIP_FILES:
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            out.append((rel, full))
    out.sort()
    return out


def main():
    files = collect()
    print("files:", len(files))

    tree_items = []
    for rel, full in files:
        with open(full, "rb") as f:
            content = base64.b64encode(f.read()).decode()
        blob = api("POST", "/repos/%s/%s/git/blobs" % (OWNER, REPO),
                   {"content": content, "encoding": "base64"})
        tree_items.append({
            "path": rel,
            "mode": "100755" if rel == "gradlew" else "100644",
            "type": "blob",
            "sha": blob["sha"],
        })
        print("  ok %-58s %8d" % (rel, os.path.getsize(full)))

    tree = api("POST", "/repos/%s/%s/git/trees" % (OWNER, REPO), {"tree": tree_items})
    print("tree:", tree["sha"])

    parent = None
    try:
        ref = api("GET", "/repos/%s/%s/git/ref/heads/main" % (OWNER, REPO))
        parent = ref["object"]["sha"]
        print("parent:", parent)
    except Exception:
        print("no parent")

    payload = {
        "message": "初始提交：地球online直装 —— YOLO11n 安卓实时识别器\n\n"
                   "- CameraX 预览 + ImageAnalysis，RGBA 直出 / YUV 兜底\n"
                   "- ONNX Runtime 跑 yolo11n，XNNPACK / NNAPI / CPU 可切\n"
                   "- 底部可拖拽抽屉控制台，每个选项带中文说明\n"
                   "- 加速组合拳总开关：RGBA + 416 输入 + NNAPI fp16 + 4 线程\n"
                   "- 三后端基准测试，取中位数\n"
                   "- GitHub Actions 自动构建 APK",
        "tree": tree["sha"],
    }
    if parent:
        payload["parents"] = [parent]

    commit = api("POST", "/repos/%s/%s/git/commits" % (OWNER, REPO), payload)
    print("commit:", commit["sha"])

    if parent:
        api("PATCH", "/repos/%s/%s/git/refs/heads/main" % (OWNER, REPO),
            {"sha": commit["sha"], "force": True})
    else:
        api("POST", "/repos/%s/%s/git/refs" % (OWNER, REPO),
            {"ref": "refs/heads/main", "sha": commit["sha"]})
    print("OK refs/heads/main ->", commit["sha"])


if __name__ == "__main__":
    main()