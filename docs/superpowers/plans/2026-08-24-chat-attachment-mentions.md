# Chat Attachment Mentions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users insert stable `@图片1` / `@视频1` / `@文件1` chips for current-draft attachments and deliver an unambiguous attachment mapping to the Agent without exposing machine hints in chat history.

**Architecture:** A focused `AttachmentMentionEditor` owns rich-text caret and menu behavior while `ChatComposer` continues to own uploads and sending. The request protocol adds optional `reference_name`; the server validates it, builds Agent-only mapping text, and persists separate display and Agent content so cross-Pod execution and session rebuild remain correct.

**Tech Stack:** React 19, TypeScript 5.9, Vitest, Testing Library, Tailwind CSS 4, Java 21, JUnit 5, Mockito, core-ng Mongo entities.

**Spec:** `docs/superpowers/specs/2026-08-24-chat-attachment-mentions-design.md`

## Global Constraints

- Mention candidates are limited to attachments in the current unsent draft; historical attachments and generated artifacts are excluded.
- Stable labels are `图片N`, `视频N`, and `文件N`; deleting an attachment never renumbers surviving labels.
- Existing upload, image paste, PDF, video understanding, Sandbox restore, theme, and session APIs remain backward compatible.
- `reference_name` is optional; requests that omit it preserve existing behavior.
- User-visible history reads `ChatMessage.content`; Agent rebuild prefers optional `ChatMessage.agentContent` and falls back to `content`.
- No new frontend dependency, Mongo collection, migration, index, model selector, or unrelated Chat redesign.
- UI colors derive from existing `--color-*` tokens; the distinctive element is the inline material chip linked to a thumbnail-bearing picker.
- Java comments remain English and new Java files use the current git user as author.

---

### Task 1: Attachment Reference Contract and Agent Mapping

**Files:**
- Modify: `core-ai-api/src/main/java/ai/core/api/server/session/SendMessageRequest.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/web/AttachmentMessageHelper.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/web/AttachmentMessageHelperTest.java`

**Interfaces:**
- Produces: optional `SendMessageAttachment.referenceName` serialized as `reference_name`.
- Produces: `AttachmentMessageHelper.buildMessageWithAttachments(SendMessageRequest)` that validates aliases and appends deterministic Agent-only mappings.
- Produces: `collectPendingFiles` and `collectMultimodalAttachments` maps that retain `referenceName` when supplied.

- [ ] **Step 1: Add failing mapping and validation tests**

Name the breaks: swapping image order, dropping a Sandbox path, accepting duplicate aliases, or changing no-mention behavior must fail a test. Add literal expectations such as:

```java
@Test
void referencedImagesMapAliasToStableAttachmentOrder() {
    var first = image("menu.png", "图片1");
    var second = image("storyboard.png", "图片2");
    var request = request("Compare @图片1 with @图片2", first, second);

    assertEquals("Compare @图片1 with @图片2\n\n"
            + "[Attached material reference: @图片1 = image file \"menu.png\" (image attachment 1)]\n"
            + "[Attached material reference: @图片2 = image file \"storyboard.png\" (image attachment 2)]\n"
            + first.url + "\n" + second.url,
            AttachmentMessageHelper.buildMessageWithAttachments(request));
}

@Test
void duplicateReferenceNamesAreRejected() {
    var request = request("Use @图片1", image("a.png", "图片1"), image("b.png", "图片1"));

    assertThrows(IllegalArgumentException.class,
            () -> AttachmentMessageHelper.buildMessageWithAttachments(request));
}

@Test
void attachmentsWithoutReferenceNamesKeepLegacyMessageShape() {
    var request = request("Inspect this", image("a.png", null));

    assertEquals("Inspect this\n\n" + request.attachments.getFirst().url,
            AttachmentMessageHelper.buildMessageWithAttachments(request));
}
```

- [ ] **Step 2: Run the target test and verify RED**

Run:

```bash
./gradlew :core-ai-server:test --tests ai.core.server.web.AttachmentMessageHelperTest
```

Expected: compilation or assertion failure because `referenceName` and mapping validation do not exist.

- [ ] **Step 3: Add the optional API field and minimal mapping implementation**

Add to `SendMessageAttachment`:

```java
@Property(name = "reference_name")
public String referenceName;
```

In `AttachmentMessageHelper`, validate every non-null alias before constructing output:

```java
private static final Pattern REFERENCE_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,32}");

private static void validateReferenceNames(List<SendMessageAttachment> attachments) {
    var names = new HashSet<String>();
    for (var attachment : attachments) {
        if (attachment.referenceName == null) continue;
        if (!REFERENCE_NAME.matcher(attachment.referenceName).matches()) {
            throw new IllegalArgumentException("invalid attachment reference name");
        }
        if (!names.add(attachment.referenceName)) {
            throw new IllegalArgumentException("duplicate attachment reference name");
        }
    }
}
```

Build mappings in original attachment order. Increment image/video ordinals only for their matching type. For Sandbox files use `/tmp/<fileName>`; for PDF retain the existing URL line; for video map only filename and ordinal so no object URL is exposed in the mapping. Copy `referenceName` into pending/multimodal metadata maps when non-null.

- [ ] **Step 4: Run the target test and verify GREEN**

Run the same Gradle command. Expected: all `AttachmentMessageHelperTest` tests pass with zero failures.

- [ ] **Step 5: Commit the contract slice**

```bash
git add core-ai-api/src/main/java/ai/core/api/server/session/SendMessageRequest.java \
  core-ai-server/src/main/java/ai/core/server/web/AttachmentMessageHelper.java \
  core-ai-server/src/test/java/ai/core/server/web/AttachmentMessageHelperTest.java
git commit -m "feat: map chat attachment references"
```

---

### Task 2: Separate Display History from Agent Context

**Files:**
- Modify: `core-ai-server/src/main/java/ai/core/server/domain/ChatMessage.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/session/ChatMessageService.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/session/SessionRebuildManager.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/messaging/SessionCommand.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/messaging/InProcessCommandHandler.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/web/AgentSessionWebServiceImpl.java`
- Modify: `core-ai-server/src/main/java/ai/core/server/web/sse/AgentMessageStreamChannelListener.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/session/ChatMessageServiceTest.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/session/SessionRebuildManagerTest.java`
- Modify: `core-ai-server/src/test/java/ai/core/server/messaging/InProcessCommandHandlerTest.java`

**Interfaces:**
- Consumes: enhanced Agent message from Task 1 and raw `SendMessageRequest.message`.
- Produces: `ChatMessage.agentContent` stored as Mongo field `agent_content`.
- Produces: `ChatMessageService.writeUserMessage(String sessionId, String content, String agentContent)`; the existing two-argument overload delegates with identical display and Agent content.
- Produces: seven-argument `SessionCommand.sendMessage(sessionId, userId, message, displayMessage, variables, pendingFiles, multimodalAttachments)` while all existing overloads remain source compatible.

- [ ] **Step 1: Add failing persistence, command, and rebuild tests**

Name the breaks: history accidentally displaying machine mappings, command payload dropping raw text, or rebuild restoring display-only content must fail. Add assertions:

```java
@Test
void userMessagePersistsDisplayAndAgentContentSeparately() {
    var service = service();
    when(service.chatMessageCollection.find(any(Query.class))).thenReturn(List.of());

    service.writeUserMessage("s-1", "Use @图片1", "Use @图片1\n\n[mapping]");

    var captor = ArgumentCaptor.forClass(ChatMessage.class);
    verify(service.chatMessageCollection).insert(captor.capture());
    assertEquals("Use @图片1", captor.getValue().content);
    assertEquals("Use @图片1\n\n[mapping]", captor.getValue().agentContent);
    verify(service.sessionRegistry).recordUserMessage("s-1", "Use @图片1");
}
```

In `InProcessCommandHandlerTest`, send an explicit display message and verify both persistence and execution:

```java
handler.handle(SessionCommand.sendMessage(
        "s-1", "u-1", "Use @图片1\n\n[mapping]", "Use @图片1", null, null, null));

verify(chatMessageService).writeUserMessage(
        "s-1", "Use @图片1", "Use @图片1\n\n[mapping]");
verify(session).sendMessage("Use @图片1\n\n[mapping]", null, null);
```

In `SessionRebuildManagerTest`, return one user `ChatMessage` with distinct `content` and `agentContent`, rebuild a session with a captured real/mocked `Agent`, and verify `restoreHistory` receives the Agent content. Add a second legacy row with null `agentContent` and verify fallback to `content`.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./gradlew :core-ai-server:test \
  --tests ai.core.server.session.ChatMessageServiceTest \
  --tests ai.core.server.session.SessionRebuildManagerTest \
  --tests ai.core.server.messaging.InProcessCommandHandlerTest
```

Expected: compilation failures for the new field/overloads or literal assertion failures showing the old single-content behavior.

- [ ] **Step 3: Implement the minimal durable split**

Add the optional Mongo field:

```java
@Field(name = "agent_content")
public String agentContent;
```

Implement the service overload so `content` and session title use display text, while `agentContent` stores enhanced text. In `SessionCommand`, include `displayMessage` only for the new overload and make legacy overloads set it equal to `message`. In the handler:

```java
var message = (String) payload.get("message");
var displayMessage = (String) payload.get("displayMessage");
if (displayMessage == null) displayMessage = message;
var agentContent = appendVideoHints(message, attachedContents);
chatMessageService.writeUserMessage(command.sessionId(), displayMessage, agentContent);
session.sendMessage(message, variables, attachedContents);
```

Use the new overload in both Web send entry points, passing `request.message` as `displayMessage`. Restore user history with `r.agentContent != null ? r.agentContent : r.content`; assistant history continues using `content`.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the same focused Gradle command. Expected: all selected tests pass with zero failures.

- [ ] **Step 5: Commit the durable history slice**

```bash
git add core-ai-server/src/main/java/ai/core/server/domain/ChatMessage.java \
  core-ai-server/src/main/java/ai/core/server/session/ChatMessageService.java \
  core-ai-server/src/main/java/ai/core/server/session/SessionRebuildManager.java \
  core-ai-server/src/main/java/ai/core/server/messaging/SessionCommand.java \
  core-ai-server/src/main/java/ai/core/server/messaging/InProcessCommandHandler.java \
  core-ai-server/src/main/java/ai/core/server/web/AgentSessionWebServiceImpl.java \
  core-ai-server/src/main/java/ai/core/server/web/sse/AgentMessageStreamChannelListener.java \
  core-ai-server/src/test/java/ai/core/server/session/ChatMessageServiceTest.java \
  core-ai-server/src/test/java/ai/core/server/session/SessionRebuildManagerTest.java \
  core-ai-server/src/test/java/ai/core/server/messaging/InProcessCommandHandlerTest.java
git commit -m "feat: preserve attachment mention context"
```

---

### Task 3: Frontend Attachment Mention Model

**Files:**
- Create: `core-ai-frontend/src/pages/chat/components/attachmentMentionModel.ts`
- Create: `core-ai-frontend/src/pages/chat/components/attachmentMentionModel.test.ts`
- Modify: `core-ai-frontend/src/pages/chat/components/ChatComposer.tsx`
- Modify: `core-ai-frontend/src/api/session.ts`
- Modify: `core-ai-frontend/src/pages/chat/Chat.tsx`

**Interfaces:**
- Produces: `AttachmentReferenceKind = 'image' | 'video' | 'file'`.
- Produces: `nextAttachmentReferenceName(contentType: string, usedNames: ReadonlySet<string>): string`.
- Produces: `withAttachmentReferenceNames(attachments, referencedIds)` to add `reference_name` only to attachments selected as rich chips.
- Extends: `ComposerAttachment` and session API attachment types with optional `reference_name?: string`.

- [ ] **Step 1: Write failing pure behavior tests**

Name the breaks: wrong media prefix, reuse of a deleted number, or sending aliases for unmentioned attachments. Use hand-derived literals:

```typescript
it('allocates the next unused stable label for each media kind', () => {
  expect(nextAttachmentReferenceName('image/png', new Set(['图片1', '图片3']))).toBe('图片2');
  expect(nextAttachmentReferenceName('video/mp4', new Set(['视频1']))).toBe('视频2');
  expect(nextAttachmentReferenceName('application/pdf', new Set())).toBe('文件1');
});

it('adds reference_name only for attachments inserted as chips', () => {
  const result = withAttachmentReferenceNames([
    attachment('a', '图片1'),
    attachment('b', '图片2'),
  ], new Set(['b']));

  expect(result[0].reference_name).toBeUndefined();
  expect(result[1].reference_name).toBe('图片2');
});
```

- [ ] **Step 2: Run the model test and verify RED**

```bash
cd core-ai-frontend && npm test -- src/pages/chat/components/attachmentMentionModel.test.ts
```

Expected: module-not-found failure because the model file does not exist.

- [ ] **Step 3: Implement the minimal pure model**

Use content type to choose the prefix and scan from 1 until the first unused label:

```typescript
export function nextAttachmentReferenceName(contentType: string, usedNames: ReadonlySet<string>): string {
  const prefix = contentType.startsWith('image/') ? '图片'
    : contentType.startsWith('video/') ? '视频'
      : '文件';
  let index = 1;
  while (usedNames.has(`${prefix}${index}`)) index += 1;
  return `${prefix}${index}`;
}
```

Define a narrow structural input type for `withAttachmentReferenceNames` so it returns copies and never mutates composer state.

- [ ] **Step 4: Run the model test and verify GREEN**

Run the same npm command. Expected: all model tests pass.

- [ ] **Step 5: Wire protocol types without changing UI behavior**

Add optional `reference_name` to `ComposerAttachment`, both `sessionApi` attachment signatures, `Chat.doStreamMessage`, and the `handleSend` mapping. Add `referenceName` to `PendingAttachment`, allocated synchronously from a `usedReferenceNamesRef` when upload begins; clear the set only on composer reset or successful send.

- [ ] **Step 6: Run frontend type/build gate**

```bash
cd core-ai-frontend && npm run build
```

Expected: TypeScript and Vite build succeed.

- [ ] **Step 7: Commit the frontend model slice**

```bash
git add core-ai-frontend/src/pages/chat/components/attachmentMentionModel.ts \
  core-ai-frontend/src/pages/chat/components/attachmentMentionModel.test.ts \
  core-ai-frontend/src/pages/chat/components/ChatComposer.tsx \
  core-ai-frontend/src/api/session.ts \
  core-ai-frontend/src/pages/chat/Chat.tsx
git commit -m "feat: model chat attachment mentions"
```

---

### Task 4: Rich Attachment Mention Editor

**Files:**
- Create: `core-ai-frontend/src/pages/chat/components/AttachmentMentionEditor.tsx`
- Create: `core-ai-frontend/src/pages/chat/components/AttachmentMentionEditor.test.tsx`
- Modify: `core-ai-frontend/src/index.css`

**Interfaces:**
- Consumes: ready/uploading attachments with `{ id, name, url, contentType, referenceName, uploading }`.
- Produces: `AttachmentMentionEditorHandle` with `focus()`, `reset()`, and `setDraft(text)`.
- Produces: `onChange({ text: string, referencedAttachmentIds: string[] })` and `onSubmit()` callbacks.
- Produces: `onPasteFiles(files: File[])` for image clipboard uploads.

- [ ] **Step 1: Write failing editor interaction tests**

Name the breaks: `@` not opening the picker, keyboard selection choosing the wrong attachment, free text falsely becoming a reference, and deletion leaving a dangling reference. Exercise the real component:

```tsx
it('inserts the highlighted attachment as a stable chip', async () => {
  const changes: AttachmentMentionValue[] = [];
  render(<AttachmentMentionEditor
    attachments={[readyAttachment('image-a', '图片1', 'menu.png')]}
    disabled={false}
    placeholder="Send a message..."
    onChange={value => changes.push(value)}
    onSubmit={() => {}}
    onPasteFiles={() => {}}
  />);

  const editor = screen.getByRole('textbox');
  await userEvent.click(editor);
  await userEvent.type(editor, '参考@');
  expect(screen.getByRole('option', { name: /图片1.*menu\.png/ })).toBeTruthy();
  await userEvent.keyboard('{Enter}');

  expect(changes.at(-1)).toEqual({ text: '参考@图片1\u00a0', referencedAttachmentIds: ['image-a'] });
});
```

Add separate tests for ArrowDown/Tab, Escape, Shift+Enter, plain-text paste, image paste callback, plain typed `@图片1` producing no ID, and rerender without an attachment removing its chip.

- [ ] **Step 2: Run the editor test and verify RED**

```bash
cd core-ai-frontend && npm test -- src/pages/chat/components/AttachmentMentionEditor.test.tsx
```

Expected: module-not-found failure because the editor does not exist.

- [ ] **Step 3: Implement flat rich-text serialization**

Use an uncontrolled `contentEditable` containing only text nodes and mention spans:

```typescript
function serialize(root: HTMLElement): AttachmentMentionValue {
  let text = '';
  const referencedAttachmentIds: string[] = [];
  root.childNodes.forEach(node => {
    if (node.nodeType === Node.TEXT_NODE) text += node.textContent ?? '';
    else if (node instanceof HTMLElement && node.dataset.attachmentId) {
      text += `@${node.dataset.referenceName}`;
      if (!referencedAttachmentIds.includes(node.dataset.attachmentId)) {
        referencedAttachmentIds.push(node.dataset.attachmentId);
      }
    }
  });
  return { text, referencedAttachmentIds };
}
```

On input, capture a Range covering the immediately preceding `@` in a text node and open a `role=listbox`. Selecting an option deletes that trigger range, inserts a `contentEditable=false` chip with `data-attachment-id` and `data-reference-name`, then inserts a non-breaking space. Keep the saved caret valid across mouse selection.

- [ ] **Step 4: Implement keyboard, paste, IME, and stale-chip behavior**

When the listbox is open, handle ArrowUp/ArrowDown, Enter/Tab, and Escape before normal send behavior. Outside the listbox, Enter calls `onSubmit`; Shift+Enter inserts a literal newline text node. During composition, defer trigger detection until `compositionend`. Paste image clipboard items through `onPasteFiles`; otherwise insert plain text. On attachment prop changes, remove chips whose IDs are absent and emit the updated value.

- [ ] **Step 5: Add scoped visual styles**

Add `.attachment-mention-editor:empty:before` for the placeholder and `.attachment-mention-chip` for a compact indigo-tinted, theme-token border. Use `:focus-visible` for the editor/list items and respect `prefers-reduced-motion`. The picker uses a white/dark theme surface, subtle shadow, 40px rows, 32px image thumbnails, and a max height with scrolling.

- [ ] **Step 6: Run editor tests and verify GREEN**

Run the same npm test command. Expected: all editor interaction tests pass without act warnings.

- [ ] **Step 7: Commit the editor slice**

```bash
git add core-ai-frontend/src/pages/chat/components/AttachmentMentionEditor.tsx \
  core-ai-frontend/src/pages/chat/components/AttachmentMentionEditor.test.tsx \
  core-ai-frontend/src/index.css
git commit -m "feat: add attachment mention editor"
```

---

### Task 5: Integrate Mentions into Chat Composer

**Files:**
- Modify: `core-ai-frontend/src/pages/chat/components/ChatComposer.tsx`
- Create: `core-ai-frontend/src/pages/chat/components/ChatComposer.test.tsx`

**Interfaces:**
- Consumes: `AttachmentMentionEditor` and `withAttachmentReferenceNames` from Tasks 3-4.
- Produces: existing `onSend(text, attachments)` callback with `reference_name` set only for rich-chip references.
- Preserves: `ChatComposerHandle.focus/reset/setDraft`, upload validation, image paste, expanded input, voice input, cancel, and attachment-only send.

- [ ] **Step 1: Write a failing composer integration test for send serialization**

Name the break: a selected visual chip must control `reference_name` on the real `onSend` payload, while an uploaded but unselected attachment remains unreferenced. Render `ChatComposer` with real child components and mock only the external credential/upload HTTP boundary. Upload two image `File` objects through the hidden file input, type `@`, select `图片2`, then send:

```tsx
expect(onSend).toHaveBeenCalledWith(expect.stringContaining('@图片2'), [
  expect.objectContaining({ file_name: 'first.png', reference_name: undefined }),
  expect.objectContaining({ file_name: 'second.png', reference_name: '图片2' }),
]);
```

The mocked credential JSON mirrors the real fields: `upload_url`, `blob_url`, `container`, and `blob_name`. Do not assert fetch call count; assert the real component's emitted payload.

- [ ] **Step 2: Run the target test and verify RED**

```bash
cd core-ai-frontend && npm test -- src/pages/chat/components/ChatComposer.test.tsx
```

Expected: the picker option is absent or the emitted attachment lacks `reference_name` because `ChatComposer` still uses a textarea.

- [ ] **Step 3: Replace the textarea with the editor**

Keep `input` as the serialized string and add `referencedAttachmentIds` state. Pass pending attachments directly to the editor. Replace textarea ref calls with `AttachmentMentionEditorHandle`. Route editor `onPasteFiles` to the existing `uploadFile` callback. Continue showing upload chips, now including `@${referenceName}` before the truncated filename.

In `handleSend`, derive ready attachments first, then call:

```typescript
const serializedAttachments = withAttachmentReferenceNames(
  readyAttachments.map(toComposerAttachment),
  new Set(referencedAttachmentIds),
);
await onSend(input.trim(), serializedAttachments);
```

Reset editor DOM, input, referenced IDs, pending attachments, expansion state, and reference-name allocation only after the send is accepted. Removing an attachment updates pending state; the editor prop effect removes its chip.

- [ ] **Step 4: Preserve send and accessibility boundaries**

Disable send when text is blank and no ready attachment exists. Do not send while every attachment is uploading. Keep status/agent disabling, Enter-to-send, Shift+Enter, cancel, voice, config, and focus behavior. Give the listbox an accessible name `Attached materials` and each option an accessible label containing alias and filename.

- [ ] **Step 5: Run focused tests and build**

```bash
cd core-ai-frontend && npm test -- \
  src/pages/chat/components/ChatComposer.test.tsx \
  src/pages/chat/components/attachmentMentionModel.test.ts \
  src/pages/chat/components/AttachmentMentionEditor.test.tsx
cd core-ai-frontend && npm run build
```

Expected: focused tests pass and Vite build exits 0.

- [ ] **Step 6: Commit the composer integration**

```bash
git add core-ai-frontend/src/pages/chat/components/ChatComposer.tsx \
  core-ai-frontend/src/pages/chat/components/ChatComposer.test.tsx
git commit -m "feat: support material mentions in chat"
```

---

### Task 6: Full Regression and Visual Verification

**Files:**
- Modify only if a failing regression or visual defect requires a scoped fix; add its failing test before production changes.

**Interfaces:**
- Consumes: completed backend and frontend slices.
- Produces: fresh evidence for automated correctness and the approved UI behavior.

- [ ] **Step 1: Run complete frontend verification**

```bash
cd core-ai-frontend && npm test
cd core-ai-frontend && npm run lint
cd core-ai-frontend && npm run build
```

Expected: all tests pass, ESLint reports zero errors, and Vite build exits 0.

- [ ] **Step 2: Run server module verification**

```bash
./gradlew --rerun-tasks :core-ai-server:check --no-daemon
```

Expected: Gradle exits 0 with no failed tests, checkstyle failures, or SpotBugs findings.

- [ ] **Step 3: Start the local app and exercise the real flow**

Run the existing local server/frontend workflow. In a new Chat draft, upload two images and one document, type `@`, select each with keyboard and mouse, remove one attachment, and send. Confirm the outgoing request contains unique `reference_name` values only for selected chips and that history reload displays only the original readable text.

- [ ] **Step 4: Capture and inspect visual states**

Capture desktop light, desktop dark, and 390px-wide screenshots with the picker open and with two chips wrapping in the editor. Check thumbnail crop, menu clipping, focus ring, chip baseline, attachment removal, and absence of horizontal page overflow. If any defect is found, write a failing component test when behavior is testable, apply the smallest CSS/component fix, and rerun Steps 1-2.

- [ ] **Step 5: Review the final diff against the spec**

```bash
git diff 79e6c030..HEAD --check
git status --short
```

Verify every acceptance criterion from the spec, confirm unrelated dirty-worktree files were not included, and report any environment-only validation that could not be completed.
