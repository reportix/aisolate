package com.reportix.aisolate.sdk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The ids of the data sources a handler reads ({@code @DataSources("hr")}): the AIsolate UI's graph
 * shows them as the handler's Source. Optional; Python's {@code data_sources=}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DataSources {
  String[] value();
}
