package com.miqroera.miqrokey.gateway.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure user-message-text extraction over the three wire protocols the proxy
 * serves (ADR-0014 P1: USER_TEXT_ONLY — only user-role text parts are ever
 * collected; system prompts, tool payloads and model replies are ignored by
 * design). Mirrors the shape knowledge of {@code CacheKeyFactory} without
 * touching the forwarded bytes.
 */
public final class RetentionTextExtractor {

    public enum Protocol {
        ANTHROPIC_MESSAGES, OPENAI_CHAT, OPENAI_RESPONSES
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @return concatenated user text (turns joined by {@code \n---\n}), or empty
     *         string when the body carries no user text.
     */
    public String extract(Protocol protocol, byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return switch (protocol) {
                case ANTHROPIC_MESSAGES -> anthropicUserText(root);
                case OPENAI_CHAT -> openaiChatUserText(root);
                case OPENAI_RESPONSES -> openaiResponsesUserText(root);
            };
        } catch (Exception e) {
            // Never fail the hot path over extraction; unparseable bodies are
            // skipped (same posture as every metadata read).
            return "";
        }
    }

    /**
     * Model-reply text (ADR-0014 增补, 2026-09-15): non-streaming JSON bodies parse
     * the assistant content; SSE bodies concatenate text deltas. Reasoning deltas,
     * tool payloads and system content stay excluded.
     *
     * @return concatenated assistant text, or empty when none is present.
     */
    public String extractOutput(Protocol protocol, byte[] body, boolean sse) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            if (sse) {
                return sseOutputText(protocol, new String(body, java.nio.charset.StandardCharsets.UTF_8));
            }
            JsonNode root = objectMapper.readTree(body);
            return switch (protocol) {
                case ANTHROPIC_MESSAGES -> anthropicOutputText(root);
                case OPENAI_CHAT -> openaiChatOutputText(root);
                case OPENAI_RESPONSES -> openaiResponsesOutputText(root);
            };
        } catch (Exception e) {
            return "";
        }
    }

    private String anthropicOutputText(JsonNode root) {
        List<String> texts = new ArrayList<>();
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText(""))) {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) {
                        texts.add(text);
                    }
                }
            }
        }
        return join(texts);
    }

    private String openaiChatOutputText(JsonNode root) {
        List<String> texts = new ArrayList<>();
        JsonNode choices = root.path("choices");
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                collectTextParts(choice.path("message").path("content"), texts);
            }
        }
        return join(texts);
    }

    private String openaiResponsesOutputText(JsonNode root) {
        List<String> texts = new ArrayList<>();
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.path("content");
                if (content.isArray()) {
                    for (JsonNode part : content) {
                        if ("output_text".equals(part.path("type").asText(""))) {
                            String text = part.path("text").asText("");
                            if (!text.isBlank()) {
                                texts.add(text);
                            }
                        }
                    }
                }
            }
        }
        String direct = root.path("output_text").asText("");
        if (texts.isEmpty() && !direct.isBlank()) {
            texts.add(direct);
        }
        return join(texts);
    }

    /**
     * Concatenates SSE text deltas; malformed or truncated tail lines are skipped.
     */
    private String sseOutputText(Protocol protocol, String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            try {
                JsonNode event = objectMapper.readTree(payload);
                String delta = switch (protocol) {
                    case ANTHROPIC_MESSAGES -> "content_block_delta".equals(event.path("type").asText(""))
                            ? event.path("delta").path("text").asText("")
                            : "";
                    case OPENAI_CHAT -> {
                        JsonNode choices = event.path("choices");
                        yield choices.isArray() && !choices.isEmpty()
                                ? choices.get(0).path("delta").path("content").asText("")
                                : "";
                    }
                    case OPENAI_RESPONSES -> "response.output_text.delta".equals(event.path("type").asText(""))
                            ? event.path("delta").asText("")
                            : "";
                };
                if (!delta.isEmpty()) {
                    sb.append(delta);
                }
            } catch (Exception ignored) {
                // Truncated tail or a non-JSON line: skip.
            }
        }
        return sb.toString();
    }

    private String anthropicUserText(JsonNode root) {
        List<String> texts = new ArrayList<>();
        JsonNode messages = root.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                if (!"user".equals(message.path("role").asText())) {
                    continue;
                }
                JsonNode content = message.path("content");
                collectTextParts(content, texts);
            }
        }
        return join(texts);
    }

    private String openaiChatUserText(JsonNode root) {
        List<String> texts = new ArrayList<>();
        JsonNode messages = root.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                if (!"user".equals(message.path("role").asText())) {
                    continue;
                }
                collectTextParts(message.path("content"), texts);
            }
        }
        return join(texts);
    }

    private String openaiResponsesUserText(JsonNode root) {
        List<String> texts = new ArrayList<>();
        JsonNode input = root.path("input");
        if (input.isArray()) {
            for (JsonNode item : input) {
                if (!"user".equals(item.path("role").asText())) {
                    continue;
                }
                collectTextParts(item.path("content"), texts);
            }
        }
        // Responses also accepts trailing plain strings as user input.
        for (JsonNode item : input) {
            if (item.isTextual() && !item.asText().isBlank()) {
                texts.add(item.asText());
            }
        }
        return join(texts);
    }

    private void collectTextParts(JsonNode content, List<String> texts) {
        if (content == null || content.isMissingNode()) {
            return;
        }
        if (content.isTextual()) {
            if (!content.asText().isBlank()) {
                texts.add(content.asText());
            }
            return;
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    if (!part.asText().isBlank()) {
                        texts.add(part.asText());
                    }
                    continue;
                }
                String type = part.path("type").asText("");
                if ("text".equals(type) || "input_text".equals(type)) {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) {
                        texts.add(text);
                    }
                }
            }
        }
    }

    private String join(List<String> texts) {
        if (texts.isEmpty()) {
            return "";
        }
        return String.join("\n---\n", texts);
    }
}
