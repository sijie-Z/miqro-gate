package com.miqroera.miqrokey.gateway.retention;

import com.miqroera.miqrokey.gateway.retention.RetentionTextExtractor.Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Retention text extraction (USER_TEXT_ONLY)")
class RetentionTextExtractorTest {

    private final RetentionTextExtractor extractor = new RetentionTextExtractor();

    private static byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("openai chat: user turns incl. content parts, system/tool ignored")
    void openaiChat() {
        String body = """
                {"model":"demo-model","messages":[
                  {"role":"system","content":"sys"},
                  {"role":"user","content":"hello world"},
                  {"role":"assistant","content":"hi"},
                  {"role":"user","content":[{"type":"text","text":"second part"},{"type":"image_url","image_url":{"url":"x"}}]}
                ]}""";
        String text = extractor.extract(Protocol.OPENAI_CHAT, json(body));
        assertThat(text).isEqualTo("hello world\n---\nsecond part");
    }

    @Test
    @DisplayName("anthropic messages: user text parts only")
    void anthropic() {
        String body = """
                {"model":"m","messages":[
                  {"role":"user","content":[{"type":"text","text":"hi there"}]},
                  {"role":"assistant","content":[{"type":"text","text":"hello"}]},
                  {"role":"user","content":"plain turn"}
                ]}""";
        String text = extractor.extract(Protocol.ANTHROPIC_MESSAGES, json(body));
        assertThat(text).isEqualTo("hi there\n---\nplain turn");
    }

    @Test
    @DisplayName("openai responses: input items and plain strings")
    void responses() {
        String body = """
                {"model":"m","input":[
                  {"role":"user","content":[{"type":"input_text","text":"alpha"},{"type":"input_image","image_url":"x"}]},
                  "trailing plain"
                ]}""";
        String text = extractor.extract(Protocol.OPENAI_RESPONSES, json(body));
        assertThat(text).isEqualTo("alpha\n---\ntrailing plain");
    }

    @Test
    @DisplayName("no user text or unparseable body yields empty")
    void emptyCases() {
        assertThat(extractor.extract(Protocol.OPENAI_CHAT,
                json("{\"messages\":[{\"role\":\"assistant\",\"content\":\"x\"}]}"))).isEmpty();
        assertThat(extractor.extract(Protocol.OPENAI_CHAT, json("not json"))).isEmpty();
        assertThat(extractor.extract(Protocol.OPENAI_CHAT, json("{}"))).isEmpty();
    }
}
