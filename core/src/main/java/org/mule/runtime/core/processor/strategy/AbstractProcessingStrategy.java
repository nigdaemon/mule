/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.processor.strategy;

import static java.util.concurrent.locks.LockSupport.parkNanos;
import static reactor.core.publisher.Flux.from;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.exception.MessagingExceptionHandler;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.processor.Sink;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.exception.MessagingException;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import reactor.core.Cancellation;
import reactor.core.publisher.BlockingSink;
import reactor.core.publisher.BlockingSink.Emission;
import reactor.core.publisher.TopicProcessor;
import reactor.core.publisher.WorkQueueProcessor;

/**
 * Determines how a list of message processors should processed.
 */
public abstract class AbstractProcessingStrategy implements ProcessingStrategy {

  public Sink getSink(FlowConstruct flowConstruct, Function<Publisher<Event>, Publisher<Event>> function) {
    TopicProcessor<Event> processor = TopicProcessor.share(false);
    Cancellation cancellation = processor.transform(function).retry().subscribe();
    BlockingSink blockingSink = processor.connectSink();

    return new ReactorSink(blockingSink, flowConstruct, cancellation);
  }

  class ReactorSink implements Sink {

    private final BlockingSink blockingSink;
    private final FlowConstruct flowConstruct;
    private final Cancellation cancellation;

    ReactorSink(BlockingSink blockingSink, FlowConstruct flowConstruct, Cancellation cancellation) {
      this.blockingSink = blockingSink;
      this.flowConstruct = flowConstruct;
      this.cancellation = cancellation;
    }

    @Override
    public void accept(Event event) {
      while (blockingSink.emit(event) != Emission.OK) {
        parkNanos(1L);
      }
    }

    @Override
    public void submit(Event event, Duration duration) {
      if (blockingSink.submit(event) < 0) {
        MessagingException rejectedException =
            new MessagingException(event, new RejectedExecutionException("Flow rejected execution of event after "
                + duration.toMillis() + "ms"));
        flowConstruct.getExceptionListener().handleException(rejectedException, event);
        event.getContext().error(rejectedException);
      }
    }

    @Override
    public boolean emit(Event event) {
      return blockingSink.emit(event) == BlockingSink.Emission.OK;
    }

    @Override
    public void complete() {
      blockingSink.complete();
      cancellation.dispose();
    }
  }
}
