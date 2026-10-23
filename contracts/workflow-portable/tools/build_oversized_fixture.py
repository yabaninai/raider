#!/usr/bin/env python3
"""Build the deterministic, schema-valid portable document over the byte limit."""

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LIMIT_BYTES = 262144
TARGET_BYTES = LIMIT_BYTES + 1
SOURCE = ROOT / "valid" / "agent.json"
FIXTURES = ROOT / "refusals" / "fixtures"


def serialize(document: dict) -> bytes:
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def main() -> None:
    for name, character in (("oversized.json", "x"), ("oversized-utf8.json", "界")):
        document = json.loads(SOURCE.read_text(encoding="utf-8"))
        text_schema = document["schemas"]["inputs"]["request"]["properties"]["text"]
        text_schema["enum"] = [""]
        base_bytes = len(serialize(document))
        padding_bytes = TARGET_BYTES - base_bytes
        if padding_bytes < 0:
            raise ValueError("The base workflow exceeds the target document size")
        if character == "x":
            padding = character * padding_bytes
        else:
            padding = character * (padding_bytes // len(character.encode("utf-8")))
            padding += "x" * (padding_bytes - len(padding.encode("utf-8")))
        text_schema["enum"] = [padding]
        payload = serialize(document)
        if len(payload) != TARGET_BYTES:
            raise AssertionError(f"Expected {TARGET_BYTES} bytes, generated {len(payload)}")
        destination = FIXTURES / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(payload)
        print(f"{destination.relative_to(ROOT)}: {len(payload)} UTF-8 bytes")


if __name__ == "__main__":
    main()
