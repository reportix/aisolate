package com.reportix.aisolate.sdk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a handler does, as a short title of two to four words ("Read HR documents"): the AIsolate
 * UI's graph shows it as the handler's node. Optional; a handler without one is listed by name.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Description {
  String value();
}
