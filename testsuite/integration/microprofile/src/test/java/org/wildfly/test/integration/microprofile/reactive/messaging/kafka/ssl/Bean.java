/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.test.integration.microprofile.reactive.messaging.kafka.ssl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.eclipse.microprofile.reactive.streams.operators.PublisherBuilder;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;

/**
 * @author <a href="mailto:kabir.khan@jboss.com">Kabir Khan</a>
 */
@ApplicationScoped
public class Bean {
    private final CountDownLatch latch = new CountDownLatch(4);
    private List<String> words = new ArrayList<>();

    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    @PreDestroy
    public void stop() {
        executorService.shutdown();
    }

    public CountDownLatch getLatch() {
        return latch;
    }

    @Outgoing("to-kafka")
    public PublisherBuilder<String> source() {
        // We need to set the following in microprofile-config.properties for this approach to work
        //  mp.messaging.incoming.from-kafka.auto.offset.reset=earliest
        return ReactiveStreams.of("hello", "reactive", "messaging", "ssl");
    }

    @Incoming("from-kafka")
    public void sink(String word) {
        words.add(word);
        latch.countDown();
    }

    public List<String> getWords() {
        return words;
    }
}
