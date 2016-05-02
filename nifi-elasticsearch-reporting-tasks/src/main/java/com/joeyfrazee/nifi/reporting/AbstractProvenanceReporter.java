/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.joeyfrazee.nifi.reporting;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.AbstractReportingTask;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.reporting.EventAccess;
import org.apache.nifi.provenance.ProvenanceEventRepository;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.ProvenanceEventType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractProvenanceReporter extends AbstractReportingTask {
    private AtomicLong lastEventId = new AtomicLong(0);

    public static final PropertyDescriptor PAGE_SIZE = new PropertyDescriptor
            .Builder().name("Page Size")
            .description("Page size for scrolling through the provenance repository")
            .required(true)
            .defaultValue("100")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor MAX_HISTORY = new PropertyDescriptor
            .Builder().name("Maximum History")
            .description(
                "How far back to look into the provenance repository to " +
                "index provenance events"
            )
            .required(true)
            .defaultValue("10000")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    protected List<PropertyDescriptor> descriptors;

    private long getLastEventId() {
        return lastEventId.get();
    }

    private void setLastEventId(long eventId) {
        lastEventId.set(eventId);
    }

    private Map<String, Object> setField(Map<String, Object> map, final String key, final Object value, final boolean overwrite) {
        Pattern p = Pattern.compile("^(\\w+)\\.(.*)$");
        Matcher m = p.matcher(key);
        if (m.find()) {
            String head = m.group(1);
            String tail = m.group(2);
            Map<String, Object> obj = (Map<String, Object>) map.get(head);
            if (obj == null) {
                obj = new HashMap<String, Object>();
            }
            Object v = setField(obj, tail, value, overwrite);
            map.put(head, v);
        }
        else {
            Object obj = map.get(key);
            if (obj != null && (obj instanceof Map)) {
                getLogger().warn("value at " + key + " is a Map");
                if (overwrite) {
                    map.put(key, value);
                }
            }
            else {
                map.put(key, value);
            }
        }
        return map;
    }

    private Map<String, Object> setField(Map<String, Object> map, final String key, final Object value) {
        return setField(map, key, value, false);
    }

    private Map<String, Object> createEventMap(ProvenanceEventRecord e) {
        final Map<String, Object> source = new HashMap<String, Object>();

        source.put("event_id", Long.valueOf(e.getEventId()));
        source.put("event_time", new Date(e.getEventTime()));
        source.put("entry_date", new Date(e.getFlowFileEntryDate()));
        source.put("lineage_start_date", new Date(e.getLineageStartDate()));
        source.put("file_size", Long.valueOf(e.getFileSize()));

        final Long previousFileSize = e.getPreviousFileSize();
        if (previousFileSize != null && previousFileSize >= 0) {
            source.put("previous_file_size", previousFileSize);
        }

        final long eventDuration = e.getEventDuration();
        if (eventDuration >= 0) {
            source.put("event_duration_millis", eventDuration);
            source.put("event_duration_seconds", eventDuration / 1000);
        }

        final ProvenanceEventType eventType = e.getEventType();
        if (eventType != null) {
            source.put("event_type", eventType.toString());
        }

        final String componentId = e.getComponentId();
        if (componentId != null) {
            source.put("component_id", componentId);
        }

        final String componentType = e.getComponentType();
        if (componentType != null) {
            source.put("component_type", componentType);
        }

        final String sourceSystemId = e.getSourceSystemFlowFileIdentifier();
        if (sourceSystemId != null) {
            source.put("source_system_id", sourceSystemId);
        }

        final String flowFileId = e.getFlowFileUuid();
        if (flowFileId != null) {
            source.put("flow_file_id", flowFileId);
        }

        final List<String> parentIds = e.getParentUuids();
        if (parentIds != null && !parentIds.isEmpty()) {
            source.put("parent_ids", parentIds);
        }

        final List<String> childIds = e.getChildUuids();
        if (childIds != null && !childIds.isEmpty()) {
            source.put("child_ids", childIds);
        }

        final String details = e.getDetails();
        if (details != null) {
            source.put("details", details);
        }

        final String relationship = e.getRelationship();
        if (relationship != null) {
            source.put("relationship", relationship);
        }

        final String sourceQueueId = e.getSourceQueueIdentifier();
        if (sourceQueueId != null) {
            source.put("source_queue_id", sourceQueueId);
        }

        final Map<String, Object> attributes = new HashMap<String, Object>();

        final Map<String, String> receivedAttributes = e.getAttributes();
        if (receivedAttributes != null && !receivedAttributes.isEmpty()) {
            for (Map.Entry<String, String> a : receivedAttributes.entrySet()) {
                setField(attributes, a.getKey(), a.getValue());
            }
        }

        final Map<String, String> updatedAttributes = e.getUpdatedAttributes();
        if (updatedAttributes != null && !updatedAttributes.isEmpty()) {
            for (Map.Entry<String, String> a : updatedAttributes.entrySet()) {
                setField(attributes, a.getKey(), a.getValue());
            }
        }

        source.put("attributes", attributes);

        return source;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(PAGE_SIZE);
        descriptors.add(MAX_HISTORY);
        return descriptors;
    }

    public abstract void indexEvent(final Map<String, Object> event, final ReportingContext context) throws IOException;

    @Override
    public void onTrigger(final ReportingContext context) {
        final EventAccess access = context.getEventAccess();
        final ProvenanceEventRepository provenance = access.getProvenanceRepository();
        final Long maxEventId = provenance.getMaxEventId();

        final int pageSize = Integer.parseInt(context.getProperty(PAGE_SIZE).getValue());
        final int maxHistory = Integer.parseInt(context.getProperty(MAX_HISTORY).getValue());

        try {
            while (maxEventId != null && getLastEventId() < maxEventId.longValue()) {
                if ((maxEventId.longValue() - getLastEventId()) > maxHistory) {
                    setLastEventId(maxEventId.longValue() - maxHistory);
                }

                final List<ProvenanceEventRecord> events = provenance.getEvents(getLastEventId(), pageSize);

                for (ProvenanceEventRecord e : events) {
                    final Map<String, Object> event = createEventMap(e);
                    indexEvent(event, context);
                }

                setLastEventId(Math.min(getLastEventId() + pageSize, maxEventId.longValue()));
            }
        }
        catch (IOException e) {
            getLogger().error(e.getMessage(), e);
            return;
        }
    }
}
