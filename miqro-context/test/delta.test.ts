import { test } from "node:test";
import assert from "node:assert/strict";
import { computeTurnDelta, conversationKeyOf } from "../src/collector/delta.js";

const SYSTEM = "You are Claude Code.\nWorking directory: /home/u/proj\n";

function user(text: string) {
  return { role: "user", content: [{ type: "text", text }] };
}
function assistant(text: string) {
  return { role: "assistant", content: [{ type: "text", text }] };
}

test("first request: the whole body is the delta (new conversation)", () => {
  const messages = [user("hi"), assistant("hello")];
  const delta = computeTurnDelta(SYSTEM, messages);
  assert.equal(delta.isNewConversation, true);
  assert.equal(delta.messages.length, 2);
  assert.equal(delta.nextWatermark.messageCount, 2);
});

test("subsequent request: only new messages are the delta", () => {
  const first = [user("hi"), assistant("hello")];
  const d1 = computeTurnDelta(SYSTEM, first);
  const second = [...first, user("next"), assistant("working")];
  const d2 = computeTurnDelta(SYSTEM, second, d1.nextWatermark);
  assert.equal(d2.isNewConversation, false);
  assert.equal(d2.messages.length, 2);
  const texts = d2.messages.map((m) => JSON.stringify(m));
  assert.ok(texts.some((t) => t.includes("next")));
  assert.ok(!texts.some((t) => t.includes("hello")), "old messages never enter the delta");
});

test("a different conversation gets no delta carry-over", () => {
  const d1 = computeTurnDelta(SYSTEM, [user("about repo A")]);
  const d2 = computeTurnDelta(SYSTEM, [user("about repo B")], d1.nextWatermark);
  assert.equal(d2.isNewConversation, true);
  assert.equal(d2.messages.length, 1);
});

test("compaction (message count shrinks): delta is the turn tail only", () => {
  const many = [user("original task"), ...Array.from({ length: 18 }, (_, i) => user(`m${i}`)), user("work")];
  const d1 = computeTurnDelta(SYSTEM, many);
  // Compaction keeps the first user message as the conversation anchor and
  // replaces the middle with a summary; the count shrinks.
  const compacted = [user("original task"), assistant("(compacted summary)"), user("continue with the current task")];
  const d2 = computeTurnDelta(SYSTEM, compacted, d1.nextWatermark);
  assert.equal(d2.compacted, true);
  assert.equal(d2.isNewConversation, false);
  assert.equal(d2.messages.length, 1);
  assert.ok(JSON.stringify(d2.messages[0]).includes("continue with the current task"));
});

test("a rewrite that replaces even the first message is a new conversation (full delta)", () => {
  const d1 = computeTurnDelta(SYSTEM, [user("old task"), assistant("ok")]);
  const rewritten = [user("summary of everything"), assistant("ok"), user("new direction")];
  const d2 = computeTurnDelta(SYSTEM, rewritten, d1.nextWatermark);
  assert.equal(d2.isNewConversation, true);
  assert.equal(d2.messages.length, 3);
});

test("conversation key is stable across growth", () => {
  const k1 = conversationKeyOf(SYSTEM, [user("hello there")]);
  const k2 = conversationKeyOf(SYSTEM, [user("hello there"), assistant("hi"), user("more")]);
  assert.equal(k1, k2);
});
