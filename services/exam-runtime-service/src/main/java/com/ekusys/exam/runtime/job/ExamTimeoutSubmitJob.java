package com.ekusys.exam.runtime.job;

import com.ekusys.exam.runtime.service.TimeoutSubmissionService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

@Component
public class ExamTimeoutSubmitJob {
    private final TimeoutSubmissionService timeoutSubmissionService;

    public ExamTimeoutSubmitJob(TimeoutSubmissionService timeoutSubmissionService) {
        this.timeoutSubmissionService = timeoutSubmissionService;
    }

    @XxlJob("examTimeoutSubmitJob")
    public void execute() {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = Math.max(XxlJobHelper.getShardTotal(), 1);
        int processed = timeoutSubmissionService.processShard(shardIndex, shardTotal);
        XxlJobHelper.log("timeout submissions processed={}, shard={}/{}", processed, shardIndex, shardTotal);
    }
}
