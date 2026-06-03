package com.reportix.aisolate.config;

/**
 * A database an airlock's code reads, by id: a tool plugin names its one in {@code dataSource()}, a
 * handler asks {@code aisolate.dataSource(id)}. The same id on the space and the airlock is one
 * data source, the deepest definition winning ({@link Configuration#dataSourcesOf}). The
 * credentials sit in the Secret {@code secretRef} names, never in a document.
 */
public record DataSource(String id, String url, String secretRef) {}
