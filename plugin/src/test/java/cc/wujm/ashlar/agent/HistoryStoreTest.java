// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import cc.wujm.ashlar.agent.model.ChatMessage;
import cc.wujm.ashlar.agent.model.ContentPart;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of {@code mcp-server/src/agent/history.test.ts}. */
class HistoryStoreTest {

    private static ChatMessage userMsg(String text) {
        return ChatMessage.user(text);
    }

    private static ChatMessage assistantMsg(String text) {
        return ChatMessage.assistantText(text);
    }

    private static ChatMessage imageMessage(String tag) {
        return ChatMessage.userParts(List.of(
                ContentPart.text("image set " + tag),
                ContentPart.imageUrl("data:image/png;base64," + tag, null)));
    }

    private static List<ContentPart> imageParts(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m.contentParts() != null)
                .flatMap(m -> m.contentParts().stream())
                .filter(ContentPart::isImage)
                .toList();
    }

    @Test
    void trimsToConfiguredNumberOfExchanges() {
        HistoryStore history = new HistoryStore(3, 30);
        for (int i = 1; i <= 5; i++) {
            history.append("p1", List.of(userMsg("request " + i), assistantMsg("reply " + i)));
        }
        List<ChatMessage> messages = history.get("p1");
        assertEquals(6, messages.size()); // the 3 most recent exchanges, 2 messages each
        assertEquals("request 3", messages.get(0).contentText());
        assertEquals("reply 3", messages.get(1).contentText());
        assertEquals("request 5", messages.get(messages.size() - 2).contentText());
        assertEquals("reply 5", messages.get(messages.size() - 1).contentText());
    }

    @Test
    void unsetPlayerHasNoHistory() {
        HistoryStore history = new HistoryStore(3, 30);
        assertEquals(List.of(), history.get("nobody"));
    }

    @Test
    void ttlEvictsIdlePlayerHistoryOnNextGet() {
        AtomicLong now = new AtomicLong(1_000_000);
        HistoryStore history = new HistoryStore(5, 10, () -> Instant.ofEpochMilli(now.get()));
        history.append("p1", List.of(userMsg("hi")));
        assertEquals(1, history.get("p1").size());

        now.addAndGet(11 * 60_000); // past the 10-minute TTL
        assertEquals(List.of(), history.get("p1"));
    }

    @Test
    void appendingAfterTtlEvictionStartsFreshHistory() {
        AtomicLong now = new AtomicLong(0);
        HistoryStore history = new HistoryStore(5, 1, () -> Instant.ofEpochMilli(now.get()));
        history.append("p1", List.of(userMsg("first")));
        now.addAndGet(2 * 60_000); // past the 1-minute TTL
        history.append("p1", List.of(userMsg("second")));

        List<ChatMessage> messages = history.get("p1");
        assertEquals(1, messages.size());
        assertEquals("second", messages.get(0).contentText());
    }

    @Test
    void getRefreshesLastUsedAtSoActivePlayerIsNotEvicted() {
        AtomicLong now = new AtomicLong(0);
        HistoryStore history = new HistoryStore(5, 10, () -> Instant.ofEpochMilli(now.get()));
        history.append("p1", List.of(userMsg("hi")));

        now.addAndGet(9 * 60_000); // still within TTL
        assertEquals(1, history.get("p1").size());

        now.addAndGet(9 * 60_000); // another 9 minutes: 18 total since append, but only 9 since the refreshing get()
        assertEquals(1, history.get("p1").size());
    }

    @Test
    void imageRedactionKeepsExactlyTheTwoNewestImages() {
        HistoryStore history = new HistoryStore(10, 30);
        for (String tag : List.of("img1", "img2", "img3", "img4")) {
            history.append("p1", List.of(imageMessage(tag)));
        }

        List<ChatMessage> messages = history.get("p1");
        List<ContentPart> images = imageParts(messages);
        assertEquals(2, images.size());
        assertEquals("data:image/png;base64,img3", images.get(0).url());
        assertEquals("data:image/png;base64,img4", images.get(1).url());

        long omittedCount = messages.stream()
                .filter(m -> m.contentParts() != null && m.contentParts().stream().anyMatch(p -> p.isText() && "[image omitted]".equals(p.text())))
                .count();
        assertEquals(2, omittedCount);
    }

    @Test
    void imageRedactionRerunsOnEveryAppend() {
        HistoryStore history = new HistoryStore(2, 30);
        history.append("p1", List.of(imageMessage("img1")));
        history.append("p1", List.of(imageMessage("img2")));
        history.append("p1", List.of(imageMessage("img3"))); // exchange 1 (img1) is trimmed out here

        List<ContentPart> images = imageParts(history.get("p1"));
        assertEquals(2, images.size());
        assertEquals("data:image/png;base64,img2", images.get(0).url());
        assertEquals("data:image/png;base64,img3", images.get(1).url());
        assertTrue(images.stream().noneMatch(p -> "data:image/png;base64,img1".equals(p.url())));
    }

    @Test
    void clearForgetsAPlayerAndReportsWhetherThereWasAnything() {
        HistoryStore store = new HistoryStore(6, 30);
        assertFalse(store.clear("p1"));
        store.append("p1", java.util.List.of(ChatMessage.user("hi"), ChatMessage.assistantText("hello")));
        assertEquals(2, store.get("p1").size());
        assertTrue(store.clear("p1"));
        assertTrue(store.get("p1").isEmpty());
    }
}
