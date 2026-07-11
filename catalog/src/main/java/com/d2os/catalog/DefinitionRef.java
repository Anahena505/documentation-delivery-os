package com.d2os.catalog;

/**
 * An immutable ({@code type}, {@code key}, {@code version}) reference to a published definition.
 */
public record DefinitionRef(String type, String key, String version) {}
