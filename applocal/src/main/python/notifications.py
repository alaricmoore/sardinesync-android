"""Run the tracker's reminder checks and hand results to Android.

On the Pi these five checks run under APScheduler and push via ntfy. Here,
NotificationWorker calls run_checks() every ~15 minutes (plus an exact alarm
for med doses); SARDINE_NOTIFY_QUEUE makes the senders queue in the database,
and the drained queue is returned as JSON for native delivery.

No Flask server involved — the checks talk straight to the database, so this
works from a background wakeup even when the app UI has never been opened.
"""
import json
from datetime import datetime

import bootstrap


def run_checks(files_dir, timezone):
    bootstrap._prepare(files_dir, timezone)
    import db
    import app as tracker

    now = datetime.now()

    # The first two mirror the Pi's once-daily cron times (they self-limit to
    # one alert per day; the hour gate keeps that alert from landing at 00:15).
    if now.hour >= int(tracker.CONFIG.get("flare_alert_hour", 8)):
        tracker._check_flare_risk_alert()
    if now.hour >= int(tracker.CONFIG.get("uv_alert_hour", 13)):
        tracker._check_uv_fetch()

    # 75-minute lookback: catches doses that came due between wakeups even if
    # a couple of cycles were dozed through. The notified flag stops repeats.
    tracker._check_and_send_reminders(lookback_minutes=75)
    tracker._check_daily_reminders()
    tracker._check_period_nudge()

    return json.dumps(db.drain_notifications())


def next_dose_epoch(files_dir, timezone):
    """Epoch millis of the next un-notified dose, or 0 if none scheduled.
    Lets Kotlin arm an exact AlarmManager alarm — med reminders shouldn't
    drift up to 15 minutes with the periodic worker."""
    bootstrap._prepare(files_dir, timezone)
    import db

    t = db.get_next_pending_dose_time(datetime.now().strftime("%Y-%m-%d %H:%M"))
    if not t:
        return 0
    try:
        dt = datetime.fromisoformat(t)
    except ValueError:
        return 0
    return int(dt.timestamp() * 1000)
