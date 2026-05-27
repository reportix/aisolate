package com.reportix.aisolate.sdk;

/** One piece of text for the model's context: where it is from and what it says. */
public record Passage(String title, String section, String body) {}
