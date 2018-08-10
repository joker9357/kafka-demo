/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;

import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * Demonstrates, using the low-level Processor APIs, how to implement the WordCount program
 * that computes a simple word occurrence histogram from an input text.
 *
 * In this example, the input stream reads from a topic named "streams-plaintext-input", where the values of messages
 * represent lines of text; and the histogram output is written to topic "streams-wordcount-processor-output" where each record
 * is an updated count of a single word.
 *
 * Before running this example you must create the input topic and the output topic (e.g. via
 * bin/kafka-topics.sh --create ...), and write some data to the input topic (e.g. via
 * bin/kafka-console-producer.sh). Otherwise you won't see any data arriving in the output topic.
 */
public class GeoFence {


    static class MyProcessorSupplier implements ProcessorSupplier<String, String> {
        class Range{
            double point_x = 0;
            double point_y = 0;
            double range = 10;

            public double getDistance(double x, double y){
                return (Math.pow(x-point_x,2) + Math.pow(y-point_y,2));
            }

            public boolean isAppear(double r){
                if(r < range * range)return true;
                return false;
            }
        }

        @Override
        public Processor<String, String> get() {
            return new Processor<String, String>() {
                private ProcessorContext context;
                private KeyValueStore<String, Integer> kvStore;

                @Override
                @SuppressWarnings("unchecked")
                public void init(final ProcessorContext context) {
                    this.context = context;
                    this.context.schedule(1000, PunctuationType.STREAM_TIME, timestamp -> {
                        try (KeyValueIterator<String, Integer> iter = kvStore.all()) {
                            System.out.println("----------- " + timestamp + " ----------- ");

                            while (iter.hasNext()) {
                                KeyValue<String, Integer> entry = iter.next();

                                System.out.println("[" + entry.key + ", " + entry.value + "]");

                                context.forward(entry.key, entry.value.toString());
                            }
                        }
                    });
                    this.kvStore = (KeyValueStore<String, Integer>) context.getStateStore("records");
                }

                @Override
                public void process(String dummy, String line) {
                    Range range = new Range();
                    String[] words = line.toLowerCase(Locale.getDefault()).split(" ");
                    String Device_ID = words[0];
                    Integer oldValue = this.kvStore.get(Device_ID);
                    System.out.println("DeviceID: " + Device_ID + " POS_X: " + words[1] + " POX_Y: " + words[2]);
                    double distance = range.getDistance(Double.parseDouble(words[1]), Double.parseDouble(words[2]));
                    int isInside = range.isAppear(distance) ? 0 : 1;
                    if (oldValue == null) {
                        System.out.println("Create new states Device_ID: " + Device_ID + " " + isInside);
                        this.kvStore.put(Device_ID, isInside);
                    } else {
                        System.out.println("Found States " + Device_ID + " " + isInside);
                        if(isInside != oldValue){
                            this.kvStore.put(Device_ID, isInside);
                            System.out.println("Found States " + Device_ID + "changed to" + isInside);
                        }else{
                            System.out.println("Found States " + Device_ID + "not changed");
                        }
                    }
                    context.commit();
                }

                @Override
                public void close() {}
            };
        }
    }

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "streams-GPS-processor");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        // setting offset reset to earliest so that we can re-run the demo code with the same pre-loaded data
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Topology builder = new Topology();
        builder.addSource("Source", "streams-input");
//        builder.addSource("Info", "GPS-info");

        builder.addProcessor("Process", new MyProcessorSupplier(), "Source", "Info");

        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.inMemoryKeyValueStore("records"),
                Serdes.String(),
                Serdes.Integer()),
                "Process");
//        builder.addStateStore(Stores.keyValueStoreBuilder(
//                Stores.inMemoryKeyValueStore("GPS"),
//                Serdes.String(),
//                Serdes.String()),
//                "Process");

        builder.addSink("Sink", "streams-output", "Process");

        final KafkaStreams streams = new KafkaStreams(builder, props);
        final CountDownLatch latch = new CountDownLatch(1);

        // attach shutdown handler to catch control-c
        Runtime.getRuntime().addShutdownHook(new Thread("streams-wordcount-shutdown-hook") {
            @Override
            public void run() {
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.start();
            latch.await();
        } catch (Throwable e) {
            System.exit(1);
        }
        System.exit(0);
    }
}