"""DB-only failure-window simulation; no Kafka broker or external service.

Run: python3 assets/code/kafka-idempotency-demo.py
Requires Python 3.8+ and SQLite 3.24+ (UPSERT).
"""
import json
import sqlite3
import tempfile
from pathlib import Path


def connect(path):
    db = sqlite3.connect(path)
    db.executescript("""
        CREATE TABLE IF NOT EXISTS account (
            id TEXT PRIMARY KEY, points INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS processed_event (
            subscriber TEXT NOT NULL,
            event_id TEXT NOT NULL,
            payload TEXT NOT NULL,
            PRIMARY KEY (subscriber, event_id)
        );
        INSERT INTO account VALUES ('member-1', 0)
        ON CONFLICT(id) DO NOTHING;
    """)
    return db


def apply_event(db, event_id, account_id, delta, fail=False):
    subscriber = 'points-credit-v1'
    payload = json.dumps([account_id, delta], separators=(',', ':'))
    with db:  # Both writes commit together, or both roll back.
        inserted = db.execute("""
            INSERT INTO processed_event VALUES (?, ?, ?)
            ON CONFLICT(subscriber, event_id) DO NOTHING
        """, (subscriber, event_id, payload)).rowcount
        if not inserted:
            old = db.execute("""
                SELECT payload FROM processed_event
                WHERE subscriber=? AND event_id=?
            """, (subscriber, event_id)).fetchone()[0]
            if old != payload:
                raise ValueError('Event ID reused with different payload')
            return False
        changed = db.execute(
            'UPDATE account SET points=points+? WHERE id=?',
            (delta, account_id),
        ).rowcount
        if changed != 1:
            raise ValueError('Unknown account')
        if fail:
            raise RuntimeError('Injected failure before DB commit')
    return True


def snapshot(db):
    points = db.execute('SELECT points FROM account').fetchone()[0]
    seen = db.execute('SELECT count(*) FROM processed_event').fetchone()[0]
    return points, seen


def main():
    with tempfile.TemporaryDirectory() as folder:
        # Baseline: one logical event applied twice, without a receipt.
        db = connect(Path(folder) / 'baseline.db')
        for _ in range(2):
            with db:
                db.execute("UPDATE account SET points=points+10 WHERE id='member-1'")
        assert snapshot(db) == (20, 0)
        db.close()
        print('without dedup: points=20')

        # Simulate a DB commit followed by a missing Kafka offset commit.
        # This integer is a model of the offset, NOT a Kafka API call.
        path = Path(folder) / 'dedup.db'
        committed_offset = 100
        db = connect(path)
        assert apply_event(db, 'event-1', 'member-1', 10)
        db.close()  # Reopen durable DB; deliberately keep modeled offset at 100.
        db = connect(path)
        assert committed_offset == 100
        assert not apply_event(db, 'event-1', 'member-1', 10)
        committed_offset = 101
        assert snapshot(db) == (10, 1)
        print('replay after DB commit: points=10, receipts=1, offset=101')
        try:
            apply_event(db, 'event-1', 'member-1', 99)
        except ValueError:
            pass
        else:
            raise AssertionError('Payload mismatch was accepted')
        assert snapshot(db) == (10, 1)
        db.close()
        print('same ID, different payload: rejected, points=10')

        db = connect(Path(folder) / 'rollback.db')
        try:
            apply_event(db, 'event-2', 'member-1', 10, fail=True)
        except RuntimeError:
            pass
        else:
            raise AssertionError('Expected injected failure')
        assert snapshot(db) == (0, 0)
        assert apply_event(db, 'event-2', 'member-1', 10)
        assert snapshot(db) == (10, 1)
        db.close()
        print('rollback then retry: points=10, receipts=1')


if __name__ == '__main__':
    main()
