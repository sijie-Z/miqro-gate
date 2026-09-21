#!/usr/bin/env python3
"""MiQroGate retention file consumer — reference implementation (ADR-0014 R4).

Reads the gateway's content-retention topic and writes each envelope, one JSON
object per line, into per-user rolling files:

    <root>/<tenantId>/<userId>/<YYYY-MM-DD>.jsonl

Semantics (see docs/retention-consumer.md):
  * at-least-once + idempotent on eventId (in-memory set seeded by scanning the
    existing files at startup, so a crash+replay does not duplicate lines);
  * offsets are committed only AFTER a batch was fully appended to disk;
  * user text never appears anywhere: values carry only base64 AES ciphertext.

Platforms should adapt this file to their own storage target (object storage
with SSE-KMS is the primary ADR target; DB output should use a unique
constraint on event_id instead of the file scan). This script is a reference —
the product CI does not execute Python.

Usage:
    python consumer-file-ref.py --bootstrap-servers HOST:PORT
        [--topic content-retention] [--group retention-file-ref]
        [--root ./retention-out] [--dry-run]

Send SIGINT (Ctrl-C) once to stop after the current batch; twice to exit
immediately. Needs: pip install -r requirements.txt  (kafka-python).
"""
import argparse
import datetime as _dt
import json
import os
import signal
import sys
from pathlib import Path

# kafka-python is imported inside main() so that --help and offline checks
# still work without the dependency installed.


def _day(occurred_at: str) -> str:
    """YYYY-MM-DD of an ISO-8601 timestamp, tolerant of trailing Z/offset."""
    text = occurred_at[:10] if isinstance(occurred_at, str) and occurred_at else ""
    try:
        return _dt.datetime.fromisoformat(occurred_at.replace("Z", "+00:00")).date().isoformat()
    except ValueError:
        return text or "unknown"


class FileSink:
    """Per-user rolling JSONL writer with on-disk eventId indexing for dedupe."""

    def __init__(self, root: Path):
        self.root = root
        self.seen: set[str] = set()
        self._open: dict[Path, object] = {}
        self._scan()

    def _scan(self) -> None:
        for path in self.root.rglob("*.jsonl"):
            try:
                with path.open("r", encoding="utf-8") as handle:
                    for line in handle:
                        event = json.loads(line)
                        if event.get("eventId"):
                            self.seen.add(event["eventId"])
            except (OSError, ValueError):
                pass  # partial tail line — safe to ignore for dedupe seeding

    def write(self, event: dict) -> bool:
        """Returns True when the event was appended (False = duplicate)."""
        event_id = event.get("eventId")
        if event_id in self.seen:
            return False
        tenant = str(event.get("tenantId", "unknown"))
        user = str(event.get("userId", "unknown"))
        day = _day(str(event.get("occurredAt", "")))
        target = self.root / tenant / user / f"{day}.jsonl"
        target.parent.mkdir(parents=True, exist_ok=True)
        handle = self._open.get(target)
        if handle is None:
            handle = target.open("a", encoding="utf-8")
            self._open[target] = handle
        handle.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")
        self.seen.add(event_id)
        return True

    def flush_and_close(self) -> None:
        for handle in self._open.values():
            handle.flush()
            os.fsync(handle.fileno())
            handle.close()
        self._open.clear()


def _summarise(event: dict) -> str:
    return (
        f"{event.get('occurredAt', '?')} ev={event.get('eventId')} "
        f"tenant={event.get('tenantId')} user={event.get('userId')} "
        f"vkey={event.get('virtualKeyId')} proto={event.get('wireProtocol')} "
        f"chars={event.get('textCharCount')} keyver={event.get('keyVersion')} "
        f"cipher={str(event.get('ciphertext', ''))[:12]}..."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--bootstrap-servers", required=True)
    parser.add_argument("--topic", default="content-retention")
    parser.add_argument("--group", default="retention-file-ref")
    parser.add_argument("--root", default="./retention-out")
    parser.add_argument("--dry-run", action="store_true", help="decode and print only")
    args = parser.parse_args()

    try:
        from kafka import KafkaConsumer  # type: ignore
        from kafka.errors import KafkaError  # type: ignore
    except ImportError:
        sys.exit("missing dependency: pip install -r scripts/retention/requirements.txt")

    consumer = KafkaConsumer(
        args.topic,
        bootstrap_servers=args.bootstrap_servers,
        group_id=args.group,
        auto_offset_reset="earliest",
        enable_auto_commit=False,
        value_deserializer=lambda raw: json.loads(raw.decode("utf-8")),
    )
    sink = None if args.dry_run else FileSink(Path(args.root))

    stop = {"flag": False}

    def _on_signal(_signum, _frame):
        stop["flag"] = True

    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)

    print(f"consuming {args.topic} as {args.group} -> {'stdout' if args.dry_run else args.root}")
    try:
        while not stop["flag"]:
            for batch in consumer.poll(timeout_ms=1000).values():
                wrote = 0
                duplicates = 0
                for record in batch:
                    event = record.value
                    if not isinstance(event, dict) or "eventId" not in event:
                        print(f"skip non-envelope record at {record.offset}: {record.value!r}")
                        continue
                    if args.dry_run:
                        print(_summarise(event))
                        wrote += 1
                        continue
                    if sink.write(event):
                        wrote += 1
                    else:
                        duplicates += 1
                if not args.dry_run:
                    sink.flush_and_close()
                # persist offsets only after the batch is durably on disk
                consumer.commit()
                if wrote or duplicates:
                    print(f"batch committed: {wrote} new, {duplicates} duplicate")
    except KafkaError as error:
        print(f"kafka error: {error}", file=sys.stderr)
        return 1
    finally:
        try:
            consumer.close()
        except Exception:
            pass
        if sink is not None:
            sink.flush_and_close()
    print("stopped cleanly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
