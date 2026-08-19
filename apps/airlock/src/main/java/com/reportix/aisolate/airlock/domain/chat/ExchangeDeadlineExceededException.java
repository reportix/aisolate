package com.reportix.aisolate.airlock.domain.chat;

import lombok.experimental.StandardException;

/**
 * The exchange ran out of its budget. Thrown rather than returned, because the model must not be
 * given the chance to carry on: the exchange ends here.
 */
@StandardException
class ExchangeDeadlineExceededException extends RuntimeException {}
