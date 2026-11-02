"""app-server end-to-end checks for the session transcript.

Usage:
  1) .\\gradlew.bat :core-ai-cli:installDist
  2) python core-ai-cli/scripts/appserver-e2e.py

The harness runs the real app-server against an offline LLM stub with user.home redirected to a
temp directory, then exercises the transcript RPCs across restart / projection / undo.
"""
import json
import os
import queue
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CLI = REPO / "build" / "core-ai-cli" / "install" / "core-ai-cli" / "bin" / "core-ai-cli.bat"
STUB_PORT = 0
WRITE_PATH = None
STUB_REQUESTS = []


def check(condition, message):
    print(("PASS  " if condition else "FAIL  ") + message, flush=True)
    if not condition:
        raise AssertionError(message)


def expect_eq(actual, expected, message):
    check(actual == expected, f"{message} (expected={expected!r}, actual={actual!r})")


def message_text(message):
    content = message.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(part.get("text", "") for part in content if isinstance(part, dict))
    return ""


def last_user_text(body):
    for message in reversed(body.get("messages", [])):
        if message.get("role") == "user":
            return message_text(message)
    return ""


def text_chunks(text):
    return [
        {"id": "1", "object": "chat.completion.chunk", "created": 0, "model": "stub-model",
         "choices": [{"index": 0, "delta": {"role": "assistant", "content": text}, "finish_reason": None}]},
        {"id": "1", "object": "chat.completion.chunk", "created": 0, "model": "stub-model",
         "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
    ]


def tool_call_chunks(arguments, name="write_file"):
    return [
        {"id": "1", "object": "chat.completion.chunk", "created": 0, "model": "stub-model",
         "choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [
             {"index": 0, "id": "call_stub_1", "type": "function",
              "function": {"name": name, "arguments": ""}}]}, "finish_reason": None}]},
        {"id": "1", "object": "chat.completion.chunk", "created": 0, "model": "stub-model",
         "choices": [{"index": 0, "delta": {"tool_calls": [
             {"index": 0, "function": {"arguments": arguments}}]}, "finish_reason": None}]},
        {"id": "1", "object": "chat.completion.chunk", "created": 0, "model": "stub-model",
         "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]},
    ]


class StubHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        STUB_REQUESTS.append(body)
        prompt = last_user_text(body)
        if "FAIL" in prompt:
            data = json.dumps({"error": {"message": "stub failure"}}).encode("utf-8")
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        has_tool_result = any(m.get("role") == "tool" for m in body.get("messages", []))
        if not has_tool_result and "FORWARD_TO=" in prompt:
            target = prompt.split("FORWARD_TO=", 1)[1].split()[0]
            arguments = json.dumps({"session_id": target, "message": "forwarded hello"}, ensure_ascii=False)
            chunks = tool_call_chunks(arguments, "send_session_message")
        elif "LARGE" in prompt:
            chunks = text_chunks("L" * 40000)
        elif "forwarded hello" in prompt:
            chunks = text_chunks("forwarded ack")
        elif "TOOL" in prompt or not has_tool_result:
            arguments = json.dumps({"file_path": WRITE_PATH, "content": "hello from stub"}, ensure_ascii=False)
            chunks = tool_call_chunks(arguments)
        else:
            chunks = text_chunks("done: file written")
        if body.get("stream"):
            usage_chunk = {"id": "1", "object": "chat.completion.chunk", "created": 0, "model": "stub-model",
                           "choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}
            payload = ("".join(f"data: {json.dumps(c)}\n\n" for c in chunks)
                       + f"data: {json.dumps(usage_chunk)}\n\n" + "data: [DONE]\n\n")
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            self.wfile.write(payload.encode("utf-8"))
        else:
            message = {"role": "assistant", "content": "done: file written"}
            response = {"id": "1", "object": "chat.completion", "created": 0, "model": "stub-model",
                        "choices": [{"index": 0, "message": message, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
            data = json.dumps(response).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass


def start_stub():
    global STUB_PORT
    server = ThreadingHTTPServer(("127.0.0.1", 0), StubHandler)
    STUB_PORT = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


class Rpc:
    """Line-delimited JSON-RPC client over the engine's stdio; frames are drained by reader threads."""

    def __init__(self, proc):
        self.proc = proc
        self.frames = queue.Queue()
        self.notifications = []
        self.stderr_lines = []
        self._next_id = 0
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()

    def _read_stdout(self):
        for line in self.proc.stdout:
            line = line.strip()
            if line:
                self.frames.put(line)

    def _read_stderr(self):
        for line in self.proc.stderr:
            self.stderr_lines.append(line.rstrip())
            del self.stderr_lines[:-400]

    def send(self, method, params=None, notify=False):
        frame = {"jsonrpc": "2.0", "method": method}
        request_id = None
        if not notify:
            self._next_id += 1
            request_id = self._next_id
            frame["id"] = request_id
        if params is not None:
            frame["params"] = params
        self.proc.stdin.write(json.dumps(frame) + "\n")
        self.proc.stdin.flush()
        return request_id

    def call(self, method, params=None, timeout=180):
        request_id = self.send(method, params)
        deadline = time.time() + timeout
        while True:
            frame = self._next_frame(deadline)
            if frame.get("id") == request_id:
                if "error" in frame:
                    raise AssertionError(f"{method} failed: {frame['error']}")
                return frame.get("result", {})
            self.notifications.append(frame)

    def wait_for(self, predicate, description, timeout=180):
        for frame in self.notifications:
            if predicate(frame):
                return frame
        deadline = time.time() + timeout
        while True:
            frame = self._next_frame(deadline)
            if predicate(frame):
                return frame
            self.notifications.append(frame)

    def wait_for_method(self, method, timeout=180):
        return self.wait_for(lambda f: f.get("method") == method, method, timeout)

    def wait_for_event(self, event_type, timeout=180):
        def matches(frame):
            return (frame.get("method") == "session/event"
                    and frame.get("params", {}).get("event", {}).get("type") == event_type)
        return self.wait_for(matches, event_type, timeout)["params"]["event"]

    def _next_frame(self, deadline):
        remaining = deadline - time.time()
        if remaining <= 0:
            raise AssertionError("timed out waiting for a frame; stderr tail:\n" + "\n".join(self.stderr_lines[-40:]))
        try:
            return json.loads(self.frames.get(timeout=remaining))
        except queue.Empty:
            raise AssertionError("timed out waiting for a frame; stderr tail:\n" + "\n".join(self.stderr_lines[-40:]))


def start_engine(home, workspace, config):
    env = os.environ.copy()
    env["JAVA_OPTS"] = f"-Duser.home={home}"
    proc = subprocess.Popen(
        ["cmd", "/c", str(CLI), "app-server", "--workspace", str(workspace), "--config", str(config)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace", cwd=str(workspace), env=env)
    return proc, Rpc(proc)


def kill_tree(proc):
    if os.name == "nt":
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)
    else:
        proc.kill()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        pass


def initialize(rpc, workspace):
    init = rpc.call("initialize", {"protocolVersion": "1.0",
                                   "client": {"name": "appserver-e2e", "version": "0"},
                                   "workspace": str(workspace), "approvalPolicy": "ask"})
    expect_eq(init.get("protocolVersion"), "1.0", "initialize returns protocolVersion")
    check("transcript" in (init.get("capabilities") or []), "capabilities include transcript")
    rpc.wait_for_method("engine/ready")


def shutdown(rpc):
    rpc.call("shutdown", {})
    try:
        rpc.proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        kill_tree(rpc.proc)
        raise AssertionError("engine did not exit after shutdown; stderr tail:\n" + "\n".join(rpc.stderr_lines[-40:]))
    expect_eq(rpc.proc.returncode, 0, "engine exits cleanly after shutdown")


def find_session_file(home, session_id, suffix):
    matches = list(home.rglob(session_id + suffix))
    check(bool(matches), f"{session_id}{suffix} exists")
    return matches[0]


def message_count(home, session_id):
    data = json.loads(find_session_file(home, session_id, ".data").read_text(encoding="utf-8"))
    return len(data.get("messages") or [])


def kinds(records):
    return [r.get("kind") for r in records.get("records", [])]


def turn_records(records, turn_id):
    return [r for r in records.get("records", []) if r.get("turn_id") == turn_id]


def assert_turn(records, message_count_value, client_turn_id, steer_client_id):
    expect_eq(records.get("source"), "transcript", "records source")
    expect_eq(records.get("total_turns"), 1, "records total_turns")
    starts = [r for r in records.get("records", []) if r.get("kind") == "turn_start"]
    expect_eq(len(starts), 1, "one turn_start")
    start = starts[0]
    turn_id = start.get("turn_id")
    check(turn_id and turn_id.startswith("t"), "turn_id is recorder minted")
    all_records = turn_records(records, turn_id)
    for kind in ("tool_start", "tool_result", "assistant_text", "turn_complete", "steer"):
        check(kind in [r.get("kind") for r in all_records], f"{kind} recorded")
    inputs = start.get("inputs") or []
    expect_eq(len(inputs), 1, "one turn input")
    expect_eq(inputs[0].get("client_turn_id"), client_turn_id, "turn client id")
    steers = [r for r in all_records if r.get("kind") == "steer"]
    expect_eq(len(steers), 2, "pending and committed steer records")
    expect_eq(steers[0].get("state"), "pending", "steer pending first")
    expect_eq(steers[1].get("state"), "committed", "steer committed second")
    expect_eq(steers[0].get("client_turn_id"), steer_client_id, "steer client id")
    expect_eq(steers[1].get("ref_seq"), steers[0].get("seq"), "steer ref_seq")
    complete = [r for r in all_records if r.get("kind") == "turn_complete"][0]
    expect_eq(complete.get("message_count"), message_count_value, "turn message_count")
    check(not complete.get("cancelled"), "turn_complete not cancelled")
    check((complete.get("input_tokens") or 0) >= 1, "turn usage recorded")


def assert_payload(rpc, session_id, records, field, expected_prefix):
    text = [r for r in records.get("records", []) if r.get("kind") == "assistant_text"][0]
    payload = rpc.call("session/recordPayload", {"sessionId": session_id, "seq": text["seq"], "field": field})
    check((payload.get("text") or "").startswith(expected_prefix), f"recordPayload {field}")
    check((payload.get("size") or 0) > 0, "recordPayload size")


def run_checks(rpc, workspace, home):
    print("-- phase 1: protocol smoke", flush=True)
    initialize(rpc, workspace)
    created = rpc.call("session/create", {})
    session_id = created["sessionId"]
    check(bool(session_id), f"session created: {session_id}")
    rpc.call("session/rename", {"sessionId": session_id, "title": "E2E renamed"})
    stats = rpc.call("session/stats", {"sessionId": session_id})
    expect_eq(stats.get("turns"), 0, "fresh session has zero turns")

    print("-- phase 2: full turn with approval and steering", flush=True)
    rpc.call("session/send", {"sessionId": session_id, "turnId": "e2e-turn-1",
                              "parts": [{"type": "text", "text": "write a file for me"}]})
    approval = rpc.wait_for_event("tool_approval_request")
    expect_eq(approval.get("toolName"), "write_file", "approval targets write_file")
    call_id = approval.get("callId")
    steer = rpc.call("session/send", {"sessionId": session_id, "turnId": "e2e-steer-2",
                                      "parts": [{"type": "text", "text": "also mention done"}]})
    check(steer.get("accepted") is True, "steer accepted")
    rpc.call("session/approve", {"sessionId": session_id, "callId": call_id, "decision": "APPROVE"})
    tool_result = rpc.wait_for_event("tool_result")
    expect_eq(tool_result.get("status"), "success", "tool_result status")
    turn = rpc.wait_for_event("turn_complete")
    check(not turn.get("cancelled"), "turn completed without cancel")
    artifact = workspace / "artifact.txt"
    check(artifact.exists(), f"stub tool wrote {artifact}")
    expect_eq(artifact.read_text(encoding="utf-8"), "hello from stub", "artifact content")

    count = message_count(home, session_id)
    records = rpc.call("session/records", {"sessionId": session_id})
    assert_turn(records, count, "e2e-turn-1", "e2e-steer-2")
    assert_payload(rpc, session_id, records, "text", "done:")
    stats = rpc.call("session/stats", {"sessionId": session_id})
    expect_eq(stats.get("turns"), 1, "stats counts one turn")
    return session_id


def run_resume_checks(rpc, workspace, home, session_id, expected_turn_id, expected_count):
    print("-- phase 3: restart and resume", flush=True)
    initialize(rpc, workspace)
    resumed = rpc.call("session/resume", {"sessionId": session_id})
    check(resumed.get("status") in ("idle", "running"), "session resumed")
    records = rpc.call("session/records", {"sessionId": session_id})
    assert_turn(records, expected_count, "e2e-turn-1", "e2e-steer-2")
    expect_eq(records.get("records")[0].get("turn_id"), expected_turn_id, "turn_id survives restart")
    stats = rpc.call("session/stats", {"sessionId": session_id})
    expect_eq(stats.get("turns"), 1, "stats survives restart")


def run_projection_checks(rpc, workspace, home, session_id):
    print("-- phase 4: legacy projection", flush=True)
    initialize(rpc, workspace)
    rpc.call("session/resume", {"sessionId": session_id})
    records = rpc.call("session/records", {"sessionId": session_id})
    expect_eq(records.get("source"), "projection", "records source is projection")
    check((records.get("total_turns") or 0) >= 1, "projection has turns")
    starts = [r for r in records.get("records", []) if r.get("kind") == "turn_start"]
    check(bool(starts), "projection has turn_start")
    turn_id = starts[0].get("turn_id")
    check(turn_id and turn_id.startswith("p"), "projection turn_id")
    for kind in ("tool_start", "tool_result", "assistant_text", "turn_complete"):
        check(kind in kinds(records), f"projection records {kind}")
    stats = rpc.call("session/stats", {"sessionId": session_id})
    check((stats.get("turns") or 0) >= 1, "stats sees projection turns")
    undo = rpc.call("session/undo", {"sessionId": session_id})
    check((undo.get("removed") or 0) > 0, "projected undo removed messages")


def run_reset_checks(rpc, workspace, home, session_id):
    print("-- phase 5: reset after projected undo", flush=True)
    initialize(rpc, workspace)
    rpc.call("session/resume", {"sessionId": session_id})
    records = rpc.call("session/records", {"sessionId": session_id})
    expect_eq(records.get("source"), "projection", "reset records source")
    expect_eq(records.get("total_turns"), 1, "undo left one projected turn")
    stats = rpc.call("session/stats", {"sessionId": session_id})
    expect_eq(stats.get("turns"), 1, "stats reflects reset")


def run_tool_turn(rpc, session_id, text):
    rpc.call("session/send", {"sessionId": session_id, "parts": [{"type": "text", "text": text}]})
    approval = rpc.wait_for_event("tool_approval_request")
    rpc.call("session/approve", {"sessionId": session_id, "callId": approval.get("callId"), "decision": "APPROVE"})
    rpc.wait_for_event("tool_result")
    turn = rpc.wait_for_event("turn_complete")
    check(not turn.get("cancelled"), "turn completed without cancel")


def run_failure_checks(rpc, workspace, home):
    print("-- phase 6: failed turn", flush=True)
    initialize(rpc, workspace)
    session_id = rpc.call("session/create", {})["sessionId"]
    rpc.call("session/send", {"sessionId": session_id, "parts": [{"type": "text", "text": "please FAIL"}]})
    error = rpc.wait_for_event("error")
    check(bool(error.get("message")), "failure error event")
    count = message_count(home, session_id)
    records = rpc.call("session/records", {"sessionId": session_id})
    expect_eq(records.get("source"), "transcript", "failure records source")
    expect_eq(records.get("total_turns"), 1, "failure turn count")
    errors = [r for r in records.get("records", []) if r.get("kind") == "error"]
    expect_eq(len(errors), 1, "one terminal error record")
    expect_eq(errors[0].get("terminal"), True, "error is terminal")
    expect_eq(errors[0].get("message_count"), count, "failure message_count")
    check("turn_complete" not in kinds(records), "failed turn has no turn_complete")
    expect_eq(rpc.call("session/stats", {"sessionId": session_id}).get("turns"), 1, "failure stats")
    return session_id


def run_failure_resume_checks(rpc, workspace, home, session_id):
    print("-- phase 7: failed turn after restart", flush=True)
    initialize(rpc, workspace)
    rpc.call("session/resume", {"sessionId": session_id})
    records = rpc.call("session/records", {"sessionId": session_id})
    expect_eq(records.get("source"), "transcript", "failed turn still transcript")
    expect_eq(records.get("total_turns"), 1, "failed turn survives restart")
    errors = [r for r in records.get("records", []) if r.get("kind") == "error"]
    expect_eq(len(errors), 1, "terminal error not duplicated")
    check(not any(r.get("synthetic") for r in records.get("records", [])), "failed turn is not re-materialized")
    expect_eq(errors[0].get("message_count"), message_count(home, session_id), "failure message_count restart")


def run_large_checks(rpc, workspace, home):
    print("-- phase 8: large field externalization", flush=True)
    initialize(rpc, workspace)
    session_id = rpc.call("session/create", {})["sessionId"]
    rpc.call("session/send", {"sessionId": session_id, "parts": [{"type": "text", "text": "please LARGE"}]})
    turn = rpc.wait_for_event("turn_complete")
    check(not turn.get("cancelled"), "large turn completed")
    records = rpc.call("session/records", {"sessionId": session_id})
    text = [r for r in records.get("records", []) if r.get("kind") == "assistant_text"][0]
    check(text.get("text_ref"), "large assistant text externalized")
    check(text.get("text") is None, "large assistant text not inline")
    payload = rpc.call("session/recordPayload", {"sessionId": session_id, "seq": text["seq"], "field": "text"})
    expect_eq(len(payload.get("text") or ""), 40000, "large payload text length")
    check((payload.get("size") or 0) >= 40000, "large payload size")
    complete = [r for r in records.get("records", []) if r.get("kind") == "turn_complete"][0]
    expect_eq(complete.get("message_count"), message_count(home, session_id), "large message_count")


def run_gap_turn(rpc, workspace, home):
    print("-- phase 9: old client gap", flush=True)
    initialize(rpc, workspace)
    session_id = rpc.call("session/create", {})["sessionId"]
    run_tool_turn(rpc, session_id, "gap turn")
    count = message_count(home, session_id)
    return session_id, count


def append_old_client_turn(home, session_id):
    path = find_session_file(home, session_id, ".data")
    data = json.loads(path.read_text(encoding="utf-8"))
    user = {"role": "user", "content": [{"type": "text", "text": "old client turn"}]}
    assistant = {"role": "assistant", "content": [{"type": "text", "text": "old client answer"}]}
    data.setdefault("messages", []).extend([user, assistant])
    data.setdefault("history", []).extend([user, assistant])
    path.write_text(json.dumps(data), encoding="utf-8")


def run_gap_checks(rpc, workspace, home, session_id):
    print("-- phase 10: gap materialization", flush=True)
    initialize(rpc, workspace)
    rpc.call("session/resume", {"sessionId": session_id})
    records = rpc.call("session/records", {"sessionId": session_id})
    expect_eq(records.get("source"), "mixed", "gap page is mixed")
    expect_eq(records.get("total_turns"), 2, "gap adds one turn")
    synthetic = [r for r in records.get("records", []) if r.get("synthetic")]
    check(bool(synthetic), "gap records are synthetic")
    old_max = max(r["seq"] for r in records.get("records", []) if not r.get("synthetic"))
    check(all(r.get("seq") > old_max for r in synthetic), "gap seq follows old records")
    check(any("old client turn" in (i.get("text") or "")
              for r in records.get("records", []) if r.get("kind") == "turn_start"
              for i in (r.get("inputs") or [])), "gap input projected")
    expect_eq(rpc.call("session/stats", {"sessionId": session_id}).get("turns"), 2, "gap stats")
    transcript = find_session_file(home, session_id, ".events.jsonl").read_text(encoding="utf-8")
    expect_eq(transcript.count('"projection_from"'), 1, "one gap session_start")


def run_gap_stability(rpc, workspace, home, session_id):
    print("-- phase 11: gap does not repeat", flush=True)
    initialize(rpc, workspace)
    rpc.call("session/resume", {"sessionId": session_id})
    records = rpc.call("session/records", {"sessionId": session_id})
    expect_eq(records.get("total_turns"), 2, "gap turn count stable")
    transcript = find_session_file(home, session_id, ".events.jsonl").read_text(encoding="utf-8")
    expect_eq(transcript.count('"projection_from"'), 1, "gap not written twice")
    expect_eq(rpc.call("session/stats", {"sessionId": session_id}).get("turns"), 2, "gap stats stable")


def run_dangling_start(rpc, workspace, home):
    print("-- phase 12: dangling steer", flush=True)
    initialize(rpc, workspace)
    session_id = rpc.call("session/create", {})["sessionId"]
    run_tool_turn(rpc, session_id, "first turn")
    first_count = message_count(home, session_id)
    rpc.call("session/send", {"sessionId": session_id, "parts": [{"type": "text", "text": "second turn TOOL"}]})
    approval = rpc.wait_for_event("tool_approval_request")
    rpc.call("session/send", {"sessionId": session_id, "turnId": "dangling-steer",
                              "parts": [{"type": "text", "text": "steer before crash"}]})
    check(approval.get("callId") is not None, "second turn waiting for approval")
    kill_tree(rpc.proc)
    return session_id, first_count


def run_dangling_checks(rpc, workspace, home, session_id, first_count):
    print("-- phase 13: dangling steer recovery", flush=True)
    initialize(rpc, workspace)
    rpc.call("session/resume", {"sessionId": session_id})
    records = rpc.call("session/records", {"sessionId": session_id})
    expect_eq(records.get("total_turns"), 2, "recovered open turn counted")
    steers = [r for r in records.get("records", []) if r.get("kind") == "steer"]
    check(any(r.get("state") == "pending" for r in steers), "dangling pending present")
    check(any(r.get("state") == "cancelled" for r in steers), "dangling pending cancelled")
    completes = [r for r in records.get("records", []) if r.get("kind") == "turn_complete"]
    check(any(r.get("cancelled") for r in completes), "open turn recovered as cancelled")
    expect_eq(message_count(home, session_id), first_count, "crash did not corrupt .data")
    expect_eq(rpc.call("session/stats", {"sessionId": session_id}).get("turns"), 2, "dangling stats")


def run_forward_checks(rpc, workspace, home):
    print("-- phase 14: forward a message to another session", flush=True)
    initialize(rpc, workspace)
    target = rpc.call("session/create", {})["sessionId"]
    source = rpc.call("session/create", {})["sessionId"]
    tools = rpc.call("tools/list", {"sessionId": source}).get("tools", [])
    check(any(t.get("name") == "send_session_message" for t in tools), "send_session_message is installed")
    rpc.call("session/send", {"sessionId": source, "parts": [{"type": "text", "text": f"FORWARD_TO={target}"}]})
    approval = rpc.wait_for_event("tool_approval_request")
    expect_eq(approval.get("toolName"), "send_session_message", "approval targets send_session_message")
    rpc.call("session/approve", {"sessionId": source, "callId": approval.get("callId"), "decision": "APPROVE"})
    result = rpc.wait_for_event("tool_result")
    expect_eq(result.get("status"), "success", "forward tool result")
    target_turn = rpc.wait_for(
        lambda frame: frame.get("method") == "session/event"
        and frame.get("params", {}).get("sessionId") == target
        and frame.get("params", {}).get("event", {}).get("type") == "turn_complete",
        "target turn_complete")
    check("forwarded ack" in (target_turn.get("params", {}).get("event", {}).get("output") or ""),
          "target session answered the forwarded message")
    history = rpc.call("session/history", {"sessionId": target, "limit": 20}).get("messages", [])
    check(any(m.get("role") == "user" and "forwarded hello" in (m.get("text") or "") for m in history),
          "target history contains the forwarded user message")
    check(any(m.get("role") == "assistant" and "forwarded ack" in (m.get("text") or "") for m in history),
          "target history contains the answer")
    source_turn = rpc.wait_for(
        lambda frame: frame.get("method") == "session/event"
        and frame.get("params", {}).get("sessionId") == source
        and frame.get("params", {}).get("event", {}).get("type") == "turn_complete",
        "source turn_complete")
    check(not source_turn.get("params", {}).get("event", {}).get("cancelled"), "source turn completed")


def main():
    global WRITE_PATH
    root = Path(tempfile.mkdtemp(prefix="appserver-e2e-"))
    home = root / "home"
    workspace = root / "ws"
    home.mkdir()
    workspace.mkdir()
    WRITE_PATH = str(workspace / "artifact.txt")
    start_stub()
    config = root / "agent.properties"
    config.write_text(
        "active.provider=openai\n"
        f"openai.api.base=http://127.0.0.1:{STUB_PORT}/v1\n"
        "openai.api.key=stub\n"
        "openai.model=stub-model\n"
        "openai.models=stub-model\n"
        "agent.max.turn=5\n"
        "agent.memory.enabled=false\n",
        encoding="utf-8")
    print(f"workdir: {root}", flush=True)

    proc = None
    rpc = None
    try:
        proc, rpc = start_engine(home, workspace, config)
        session_id = run_checks(rpc, workspace, home)
        shutdown(rpc)
        expected_count = message_count(home, session_id)
        expected_turn_id = read_turn_id(home, session_id)

        print("-- restarting engine for resume checks", flush=True)
        proc, rpc = start_engine(home, workspace, config)
        run_resume_checks(rpc, workspace, home, session_id, expected_turn_id, expected_count)
        shutdown(rpc)

        find_session_file(home, session_id, ".events.jsonl").unlink()
        proc, rpc = start_engine(home, workspace, config)
        run_projection_checks(rpc, workspace, home, session_id)
        shutdown(rpc)

        proc, rpc = start_engine(home, workspace, config)
        run_reset_checks(rpc, workspace, home, session_id)
        shutdown(rpc)

        proc, rpc = start_engine(home, workspace, config)
        failure_session = run_failure_checks(rpc, workspace, home)
        shutdown(rpc)
        proc, rpc = start_engine(home, workspace, config)
        run_failure_resume_checks(rpc, workspace, home, failure_session)
        shutdown(rpc)

        proc, rpc = start_engine(home, workspace, config)
        run_large_checks(rpc, workspace, home)
        shutdown(rpc)

        proc, rpc = start_engine(home, workspace, config)
        gap_session, gap_count = run_gap_turn(rpc, workspace, home)
        shutdown(rpc)
        append_old_client_turn(home, gap_session)
        proc, rpc = start_engine(home, workspace, config)
        run_gap_checks(rpc, workspace, home, gap_session)
        shutdown(rpc)
        proc, rpc = start_engine(home, workspace, config)
        run_gap_stability(rpc, workspace, home, gap_session)
        shutdown(rpc)

        proc, rpc = start_engine(home, workspace, config)
        dangling_session, first_count = run_dangling_start(rpc, workspace, home)
        proc, rpc = start_engine(home, workspace, config)
        run_dangling_checks(rpc, workspace, home, dangling_session, first_count)
        shutdown(rpc)

        proc, rpc = start_engine(home, workspace, config)
        run_forward_checks(rpc, workspace, home)
        shutdown(rpc)
        print("ALL CHECKS PASSED", flush=True)
    finally:
        if proc is not None:
            try:
                proc.stdin.close()
            except OSError:
                pass
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                kill_tree(proc)


def read_turn_id(home, session_id):
    # The first engine is already gone; the id is the file's first turn_start turn_id.
    transcript = find_session_file(home, session_id, ".events.jsonl")
    for line in transcript.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        if record.get("kind") == "turn_start":
            return record.get("turn_id")
    raise AssertionError("turn_start missing from transcript")


if __name__ == "__main__":
    main()
