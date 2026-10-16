package com.example.shop.common.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class JobWorker {

	private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

	private final JobRepository jobs;
	private final Map<String, JobHandler> handlers;

	public JobWorker(JobRepository jobs, List<JobHandler> handlerList) {
		this.jobs = jobs;
		this.handlers = handlerList.stream()
				.collect(Collectors.toMap(JobHandler::type, h -> h));
	}

	@Scheduled(fixedDelay = 2000)
	@Transactional
	public void poll() {
		for (Job job : jobs.lockNextBatch(10)) {
			try {
				JobHandler handler = handlers.get(job.getType());
				if (handler == null) {
					throw new IllegalStateException("Không có handler cho " + job.getType());
				}
				handler.handle(job.getPayload());
				job.markDone();
			} catch (Exception e) {
				log.warn("Job {} lỗi", job.getType(), e);
				job.markFailed(e.getMessage());
			}
		}
		// các thay đổi trạng thái được flush khi transaction commit
	}
}
