---
id: protobuf
title: "Protobuf"
---

<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
  -->


This Apache Druid extension enables Druid to ingest and understand the Protobuf data format. Make sure to [include](../../development/extensions.md#loading-extensions) `druid-protobuf-extensions` as an extension.

The `druid-protobuf-extensions` provides the [Protobuf Parser](../../ingestion/data-formats.md#protobuf-parser)
for [stream ingestion](../../ingestion/index.md#streaming). See corresponding docs for details.

## Example: Load Protobuf messages from Kafka

This example demonstrates how to load Protobuf messages from Kafka.  Please read the [Load from Kafka tutorial](../../tutorials/tutorial-kafka.md) first.  This example will use the same "metrics" dataset.

Files used in this example are found at `./examples/quickstart/protobuf` in your Druid directory.

- We will use [Kafka Indexing Service](./kafka-ingestion.md).
- Kafka broker host is `localhost:9092`.
- Kafka topic is `metrics_pb` instead of `metrics`.
- datasource name is `metrics-kafka-pb` instead of `metrics-kafka` to avoid the confusion.

Here is the metrics JSON example.

```json
{
  "unit": "milliseconds",
  "http_method": "GET",
  "value": 44,
  "timestamp": "2017-04-06T02:36:22Z",
  "http_code": "200",
  "page": "/",
  "metricType": "request/latency",
  "server": "www1.example.com"
}
```

### Proto file

The proto file should look like this.  Save it as metrics.proto.

```
syntax = "proto3";
message Metrics {
  string unit = 1;
  string http_method = 2;
  int32 value = 3;
  string timestamp = 4;
  string http_code = 5;
  string page = 6;
  string metricType = 7;
  string server = 8;
}
```

### Descriptor file

Using the `protoc` Protobuf compiler to generate the descriptor file.  Save the metrics.desc file either in the classpath or reachable by URL.  In this example the descriptor file was saved at /tmp/metrics.desc.

```
protoc -o /tmp/metrics.desc metrics.proto
```

### Supervisor spec JSON

Below is the complete Supervisor spec JSON to be submitted to the Overlord.
Please make sure these keys are properly configured for successful ingestion.

- `descriptor` for the descriptor file URL.
- `protoMessageType` from the proto definition.
- parseSpec `format` must be `json`.
- `topic` to subscribe.  The topic is "metrics_pb" instead of "metrics".
- `bootstrap.server` is the Kafka broker host.

```json
{
  "type": "kafka",
  "dataSchema": {
    "dataSource": "metrics-kafka2",
    "parser": {
      "type": "protobuf",
      "descriptor": "file:///tmp/metrics.desc",
      "protoMessageType": "Metrics",
      "parseSpec": {
        "format": "json",
        "timestampSpec": {
          "column": "timestamp",
          "format": "auto"
        },
        "dimensionsSpec": {
          "dimensions": [
            "unit",
            "http_method",
            "http_code",
            "page",
            "metricType",
            "server"
          ],
          "dimensionExclusions": [
            "timestamp",
            "value"
          ]
        }
      }
    },
    "metricsSpec": [
      {
        "name": "count",
        "type": "count"
      },
      {
        "name": "value_sum",
        "fieldName": "value",
        "type": "doubleSum"
      },
      {
        "name": "value_min",
        "fieldName": "value",
        "type": "doubleMin"
      },
      {
        "name": "value_max",
        "fieldName": "value",
        "type": "doubleMax"
      }
    ],
    "granularitySpec": {
      "type": "uniform",
      "segmentGranularity": "HOUR",
      "queryGranularity": "NONE"
    }
  },
  "tuningConfig": {
    "type": "kafka",
    "maxRowsPerSegment": 5000000
  },
  "ioConfig": {
    "topic": "metrics_pb",
    "consumerProperties": {
      "bootstrap.servers": "localhost:9092"
    },
    "taskCount": 1,
    "replicas": 1,
    "taskDuration": "PT1H"
  }
}
```

## Kafka Producer

Here is the sample script that publishes the metrics to Kafka in Protobuf format.

1. Run `protoc` again with the Python binding option.  This command generates `metrics_pb2.py` file.
 ```
  protoc -o metrics.desc metrics.proto --python_out=.
 ```

2. Create Kafka producer script.

This script requires `protobuf` and `kafka-python` modules.

```python
#!/usr/bin/env python

import sys
import json

from kafka import KafkaProducer
from metrics_pb2 import Metrics

producer = KafkaProducer(bootstrap_servers='localhost:9092')
topic = 'metrics_pb'
metrics = Metrics()

for row in iter(sys.stdin):
    d = json.loads(row)
    for k, v in d.items():
        setattr(metrics, k, v)
    pb = metrics.SerializeToString()
    producer.send(topic, pb)
```

3. run producer

```
./bin/generate-example-metrics | ./pb_publisher.py
```

4. test

```
kafka-console-consumer --zookeeper localhost --topic metrics_pb
```

It should print messages like this

```
millisecondsGETR"2017-04-06T03:23:56Z*2002/list:request/latencyBwww1.example.com
```
