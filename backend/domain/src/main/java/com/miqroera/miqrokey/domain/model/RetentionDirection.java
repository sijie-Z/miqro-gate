package com.miqroera.miqrokey.domain.model;

/**
 * Which side of the exchange a retention envelope carries (ADR-0014 增补,
 * 2026-09-15): {@code INPUT} = user message text, {@code OUTPUT} = model reply
 * text. Tool payloads and system prompts are never collected on either side.
 */
public enum RetentionDirection {
    INPUT, OUTPUT
}
