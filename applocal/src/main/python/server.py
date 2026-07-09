"""Milestone 1: prove the spine.

Gradle -> Chaquopy -> CPython -> Flask -> 127.0.0.1 -> WebView.
At milestone 2 this file is replaced by the real sardinetracker app.py.
"""
from flask import Flask

app = Flask(__name__)

PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>sardinetracker</title>
<style>
  body { background:#14141b; color:#e8e6f0; font-family: Georgia, serif;
         display:grid; place-items:center; min-height:95vh; margin:0; }
  main { text-align:center; padding:2rem; }
  h1 { font-weight:600; }
  h1 span { color:#97a3ec; }
  p { color:#a8a5ba; max-width:34ch; line-height:1.6; }
  code { font-family:monospace; color:#66bb6a; }
</style>
</head>
<body>
<main>
  <h1>sardine<span>tracker</span></h1>
  <p>This page is being served by Flask, by Python, by this phone,
     to this phone, at <code>{host}</code>.</p>
  <p>No Pi. No cloud. Airplane mode would not stop it.</p>
  <p>Python {version} &middot; milestone 1</p>
</main>
</body>
</html>"""


@app.route("/")
def hello():
    import sys
    from flask import request
    # .replace, not .format — the CSS braces in PAGE are format landmines
    return PAGE.replace("{host}", request.host).replace(
        "{version}", sys.version.split()[0])


@app.route("/health")
def health():
    """Readiness probe for the Kotlin side."""
    return {"ok": True}


def run(port):
    # threaded=True: the WebView and the future sync bridge can call
    # concurrently. Loopback bind only — nothing on the network can reach it.
    app.run(host="127.0.0.1", port=port, threaded=True)
