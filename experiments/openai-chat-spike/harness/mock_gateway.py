#!/usr/bin/env python3
"""Local OpenAI-compatible mock gateway for SPIKE-OAI-01.

Implements POST /v1/chat/completions with:
  - Bearer auth check (401 + OpenAI-style error body on mismatch);
  - non-streaming JSON response;
  - SSE streaming (chunked transfer) with deliberate frame fragmentation
    (each SSE frame split across two HTTP chunks) to exercise reassembly;
  - scenarios via X-Spike-Scenario: text | stream-text | stream-tool | stream-slow.
Client disconnects during streaming are logged to the file in SPIKE_DISCONNECT_LOG.
"""
from __future__ import annotations

import http.server
import json
import os
import threading
import time

TEXT_PARTS = ["Fragments ", "of the ", "answer ", "with русский ✓"]
USAGE = {"prompt_tokens": 17, "completion_tokens": 9, "total_tokens": 26}
API_KEY = "test-key-123"


class Gateway(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send_json(self, status: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _chunk(self, data: bytes):
        self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
        self.wfile.flush()

    def _sse_frame(self, payload: str):
        data = f"data: {payload}\n\n".encode("utf-8")
        # fragment every frame across two HTTP chunks (reassembly test)
        half = max(1, len(data) // 2)
        self._chunk(data[:half])
        time.sleep(0.01)
        self._chunk(data[half:])

    def _stream(self, scenario: str):
        if scenario == "hang-headers":
            # delay BEFORE the status line: exercises true before-headers cancel
            time.sleep(5)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def chunk_msg(delta=None, finish=None, usage=None):
            obj = {}
            if delta is not None or finish is not None:
                obj["choices"] = [{"index": 0, "delta": delta if delta is not None else {},
                                   "finish_reason": finish}]
            else:
                obj["choices"] = []
            if usage is not None:
                obj["usage"] = usage
            self._sse_frame(json.dumps(obj, ensure_ascii=False))

        try:
            if scenario == "stream-text":
                chunk_msg(delta={"role": "assistant"})
                for part in TEXT_PARTS:
                    chunk_msg(delta={"content": part})
                chunk_msg(finish="stop")
                chunk_msg(usage=USAGE)
            elif scenario == "stream-tool":
                chunk_msg(delta={"role": "assistant"})
                chunk_msg(delta={"tool_calls": [{
                    "index": 0, "id": "call_1", "type": "function",
                    "function": {"name": "fs.read", "arguments": "{\"pa"}}]})
                chunk_msg(delta={"tool_calls": [{
                    "index": 0,
                    "function": {"arguments": "th\": \"README.md\"}"}}]})
                chunk_msg(finish="tool_calls")
                chunk_msg(usage=USAGE)
            elif scenario == "stream-slow":
                for i in range(60):
                    chunk_msg(delta={"content": f"slow-{i} "})
                    time.sleep(0.2)
            if scenario == "stream-malformed":
                # one broken JSON payload followed by a normal terminal event:
                # the client must reject the stream, not ignore the bad frame
                self._sse_frame('{"choices":[{"index":0,"delta":{"content":"ok"}],"brok')
                self._sse_frame("[DONE]")
                self.wfile.write(b"0\r\n\r\n")
            elif scenario == "stream-eof":
                # hard close after two chunks: no [DONE], no terminal chunk
                self._chunk(b"data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"par\"}}]}\n\n")
                self._chunk(b"data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tial\"}}]}\n\n")
                self.close_connection = True
                return
            elif scenario == "hang-headers":
                self._sse_frame('{"choices":[{"index":0,"delta":{"content":"late"}}]}')
                self._sse_frame("[DONE]")
                self.wfile.write(b"0\r\n\r\n")
            else:
                self._sse_frame("[DONE]")
                self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            log = os.environ.get("SPIKE_DISCONNECT_LOG")
            if log:
                with open(log, "a") as fh:
                    fh.write(f"client disconnected ({scenario})\n")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            json.loads(raw)
        except json.JSONDecodeError:
            self._send_json(400, {"error": {"message": "invalid json"}})
            return
        auth = self.headers.get("Authorization", "")
        if auth != f"Bearer {API_KEY}":
            self._send_json(401, {"error": {
                "message": "Invalid API key", "type": "invalid_request_error",
                "code": "invalid_api_key"}})
            return
        scenario = self.headers.get("X-Spike-Scenario", "text")
        if scenario == "text":
            self._send_json(200, {
                "id": "chatcmpl-spike-1",
                "choices": [{"index": 0,
                             "message": {"role": "assistant",
                                         "content": "".join(TEXT_PARTS)},
                             "finish_reason": "stop"}],
                "usage": USAGE})
        elif scenario in ("stream-text", "stream-tool", "stream-slow",
                          "stream-malformed", "stream-eof", "hang-headers"):
            self._stream(scenario)
        else:
            self._send_json(400, {"error": {"message": f"unknown scenario {scenario}"}})


def start() -> tuple[str, http.server.ThreadingHTTPServer]:
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Gateway)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{srv.server_address[1]}", srv


if __name__ == "__main__":
    url, srv = start()
    print(f"mock gateway on {url}", flush=True)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        srv.shutdown()
