/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.eagle.jpm.mr.history.parser;

import org.apache.eagle.jpm.mr.history.common.JHFConfigManager;
import org.apache.eagle.jpm.mr.history.entities.JobBaseAPIEntity;
import org.apache.eagle.jpm.mr.history.entities.TaskAttemptCounterAPIEntity;
import org.apache.eagle.jpm.mr.history.entities.TaskAttemptExecutionAPIEntity;
import org.apache.eagle.service.client.IEagleServiceClient;
import org.apache.eagle.service.client.impl.EagleServiceClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TaskAttemptCounterListener implements HistoryJobEntityCreationListener {
    private static final Logger logger = LoggerFactory.getLogger(TaskAttemptCounterListener.class);
    private static final int BATCH_SIZE = 1000;
    private Map<CounterKey, CounterValue> counters = new HashMap<>();
    private JHFConfigManager configManager;

    public TaskAttemptCounterListener(JHFConfigManager configManager) {
        this.configManager = configManager;
    }

    private static class CounterKey {
        Map<String, String> tags = new HashMap<>();
        long timestamp;
        
        @Override
        public boolean equals(Object thatKey) {
            if (!(thatKey instanceof CounterKey))
                return false;
            CounterKey that = (CounterKey)thatKey;
            if (that.tags.equals(this.tags) && that.timestamp == this.timestamp)
                return true;
            return false;
        }
        
        @Override
        public int hashCode(){
            return tags.hashCode() ^ Long.valueOf(timestamp).hashCode();
        }
    }
    
    private static class CounterValue {
        int totalCount;
        int failedCount;
        int killedCount;
    }
    
    @Override
    public void jobEntityCreated(JobBaseAPIEntity entity) throws Exception {
        if (!(entity instanceof TaskAttemptExecutionAPIEntity)) {
            return;
        }
        
        TaskAttemptExecutionAPIEntity e = (TaskAttemptExecutionAPIEntity)entity;
        
        Map<String, String> tags = new HashMap<>();
        tags.put(EagleJobTagName.SITE.toString(), e.getTags().get(EagleJobTagName.SITE.toString()));
        tags.put(EagleJobTagName.NORM_JOB_NAME.toString(), e.getTags().get(EagleJobTagName.NORM_JOB_NAME.toString()));
        tags.put(EagleJobTagName.RACK.toString(), e.getTags().get(EagleJobTagName.RACK.toString()));
        tags.put(EagleJobTagName.HOSTNAME.toString(), e.getTags().get(EagleJobTagName.HOSTNAME.toString()));
        tags.put(EagleJobTagName.JOB_ID.toString(), e.getTags().get(EagleJobTagName.JOB_ID.toString()));
        tags.put(EagleJobTagName.TASK_TYPE.toString(), e.getTags().get(EagleJobTagName.TASK_TYPE.toString()));

        CounterKey key = new CounterKey();
        key.tags = tags;
        key.timestamp = roundToMinute(e.getEndTime());
        
        CounterValue value = counters.get(key);
        if (value == null) {
            value = new CounterValue();
            counters.put(key, value);
        }
        
        if (e.getTaskStatus().equals(EagleTaskStatus.FAILED.name())) {
            value.failedCount++;
        } else if(e.getTaskStatus().equals(EagleTaskStatus.KILLED.name())) {
            value.killedCount++;
        }
        value.totalCount++;
    }
    
    private long roundToMinute(long timestamp) {
        GregorianCalendar cal = new GregorianCalendar();
        cal.setTimeInMillis(timestamp);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
    
    @Override
    public void flush() throws Exception {
        JHFConfigManager.EagleServiceConfig eagleServiceConfig = configManager.getEagleServiceConfig();
        JHFConfigManager.JobExtractorConfig jobExtractorConfig = configManager.getJobExtractorConfig();
        IEagleServiceClient client = new EagleServiceClientImpl(
                eagleServiceConfig.eagleServiceHost,
                eagleServiceConfig.eagleServicePort,
                eagleServiceConfig.username,
                eagleServiceConfig.password);

        client.getJerseyClient().setReadTimeout(jobExtractorConfig.readTimeoutSeconds * 1000);
        List<TaskAttemptCounterAPIEntity> list = new ArrayList<>();
        logger.info("start flushing TaskAttemptCounter entities of total number " + counters.size());
        // create entity
        for (Map.Entry<CounterKey, CounterValue> entry : counters.entrySet()) {
            CounterKey key = entry.getKey();
            CounterValue value = entry.getValue();
            TaskAttemptCounterAPIEntity entity = new TaskAttemptCounterAPIEntity();
            entity.setTags(key.tags);
            entity.setTimestamp(key.timestamp);
            entity.setTotalCount(value.totalCount);
            entity.setFailedCount(value.failedCount);
            entity.setKilledCount(value.killedCount);
            list.add(entity);
            
            if (list.size() >= BATCH_SIZE) {
                logger.info("start flushing TaskAttemptCounter " + list.size());
                client.create(list);
                logger.info("end  flushing TaskAttemptCounter " + list.size());
                list.clear();
            }
        }

        logger.info("start flushing rest of TaskAttemptCounter " + list.size());
        client.create(list);
        logger.info("end  flushing rest of TaskAttemptCounter " + list.size());
        logger.info("end  flushing TaskAttemptCounter entities of total number " + counters.size());
        counters.clear();
        list.clear();
        client.getJerseyClient().destroy();
        client.close();
    }
}