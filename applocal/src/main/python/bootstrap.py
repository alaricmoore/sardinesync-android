"""Boot the embedded sardinetracker server.

Called from Kotlin with the app's private files dir. _prepare() does the
platform setup a fresh self-hosted install would need (env switches,
config.json, database, the one user) and is shared with notifications.py,
which runs the reminder checks WITHOUT a server. start() adds Flask on top —
the SAME code that runs on a Pi (see DATA_DIR in tracker/app.py).
"""
import json
import os
import secrets
import sys

TRACKER_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tracker")

_prepared = False


def _prepare(files_dir, timezone):
    """Idempotent: env switches, sys.path, cwd, config, database, sole user."""
    global _prepared
    if _prepared:
        return
    if TRACKER_DIR not in sys.path:
        sys.path.insert(0, TRACKER_DIR)

    os.makedirs(files_dir, exist_ok=True)
    os.environ["SARDINE_DATA_DIR"] = files_dir
    os.environ["SARDINE_EMBEDDED"] = "1"
    # Notifications queue in the DB for NotificationWorker to deliver
    # natively — no ntfy on a phone that notifies itself.
    os.environ["SARDINE_NOTIFY_QUEUE"] = "1"
    # setup.py and some legacy paths are CWD-relative; make CWD the data dir
    # so every spelling of "next to the app" lands in app-private storage.
    os.chdir(files_dir)

    _ensure_config(files_dir, timezone)
    _ensure_user()
    _prepared = True


def _ensure_config(files_dir, timezone):
    path = os.path.join(files_dir, "config.json")
    if os.path.exists(path):
        return
    config = {
        "patient_name": "",
        "timezone": timezone,
        "temp_baseline_f": 97.6,
        "app_version": "2.0.0",
        "debug": False,
        # Exactly one person owns this phone: skip the login screen.
        "single_user_mode": True,
        # Both secrets are per-install and never leave the device. The
        # api_token gates /api/health-sync for the sync bridge.
        "secret_key": secrets.token_hex(32),
        "api_token": secrets.token_hex(16),
        "track_cycle": False,
    }
    with open(path, "w") as f:
        json.dump(config, f, indent=2)


def _ensure_user():
    import bcrypt

    import db
    import setup

    setup.create_database()  # CREATE IF NOT EXISTS — idempotent
    db.run_migrations()
    if not db.get_sole_user():
        # single_user_mode signs in automatically, so the password is never
        # typed — but the column is NOT NULL and honesty demands a real hash.
        throwaway = secrets.token_urlsafe(24)
        pw_hash = bcrypt.hashpw(throwaway.encode(), bcrypt.gensalt()).decode()
        db.create_user("me", "Me", pw_hash, is_admin=True)


def start(files_dir, port, timezone):
    _prepare(files_dir, timezone)
    from app import app  # the vendored tracker — import AFTER env is set
    app.run(host="127.0.0.1", port=port, threaded=True)
