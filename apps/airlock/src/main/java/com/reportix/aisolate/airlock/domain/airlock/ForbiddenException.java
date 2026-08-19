package com.reportix.aisolate.airlock.domain.airlock;

import lombok.experimental.StandardException;

/** Answered with 403 "forbidden"; the reason never reaches the client. */
@StandardException
public class ForbiddenException extends RuntimeException {}
