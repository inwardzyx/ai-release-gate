package com.arg.gate.service;

import com.arg.gate.client.ExecutorClient;
import com.arg.gate.client.ReleaseClient;
import com.arg.gate.repo.GateJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 门禁调度：把"任务"变成"提交给执行器"，再把"回调"变成"回写发布状态"。
 *
 * <p>★★ 三个设计点（面试会被追问）：
 * <ol>
 *   <li><b>结果只写一次</b>：回调用 {@code passed IS NULL} 做条件更新，重复回调直接忽略。
 *       否则重复回调会把"已回滚"的发布再回滚一次，指针就错了。</li>
 *   <li><b>提交失败可重试</b>：执行器不可达时任务留在 QUEUED，扫描器（每 3s）按
 *       {@code attempts < 5} 重投；因为执行器按 jobId 幂等，重投不会重复跑。</li>
 *   <li><b>回写失败要能看见</b>：如果 release-svc 挂了，这里记 warn 并让任务保持"已判定"——
 *       下一次扫描不会再处理它（passed 已非 NULL），所以需要人工/告警。
 *       <b>这是本版已知缺口</b>：正确做法是"回写失败则回滚本地判定"。先记下来，别假装没有。</li>
 * </ol>
 */
@Service
public class GateService {

    private static final Logger log = LoggerFactory.getLogger(GateService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final GateJobRepository repo;
    private final ReleaseClient releaseClient;
    private final ExecutorClient executorClient;

    public GateService(GateJobRepository repo, ReleaseClient releaseClient, ExecutorClient executorClient) {
        this.repo = repo;
        this.releaseClient = releaseClient;
        this.executorClient = executorClient;
    }

    /** 建任务后立刻尝试提交（失败就留给扫描器）。 */
    public void dispatch(long jobId, long releaseId) {
        Optional<Map<String, Object>> job = repo.findById(jobId);
        if (job.isEmpty() || !"QUEUED".equals(job.get().get("state"))) {
            return;
        }
        try {
            BigDecimal threshold = releaseClient.thresholdOf(releaseId);
            if (!repo.markRunning(jobId)) {
                return;                       // 别人抢先提交了
            }
            executorClient.submit(jobId, releaseId,
                    String.valueOf(job.get().get("prompt_key")),
                    ((Number) job.get().get("version")).intValue(),
                    threshold);
            log.info("已提交评测 jobId={} releaseId={} threshold={}", jobId, releaseId, threshold);
        } catch (Exception e) {
            // ★ 提交失败 ⇒ 立刻放回 QUEUED：这样"快速重投"只覆盖"没提交成功"，
            //   而 RUNNING 可以放心地用长租约（见 GateJobRepository.findStale 的注释）。
            repo.markQueuedAgain(jobId);
            log.warn("提交评测失败，已放回 QUEUED 等扫描器重投 jobId={} err={}", jobId, e.getMessage());
        }
    }

    /**
     * 处理执行器回调。
     *
     * @return true = 这次回调真的改变了状态；false = 重复回调（已幂等忽略）
     */
    public boolean onResult(long jobId, BigDecimal score, boolean passed, String detail) {
        Optional<Map<String, Object>> job = repo.findById(jobId);
        if (job.isEmpty()) {
            throw new IllegalArgumentException("任务不存在: " + jobId);
        }
        boolean changed = repo.finish(jobId, score, passed, detail);
        if (!changed) {
            log.info("重复回调，已幂等忽略 jobId={}", jobId);
            return false;
        }
        long releaseId = ((Number) job.get().get("release_id")).longValue();
        try {
            releaseClient.reportGateResult(releaseId, score, passed);
            log.info("门禁结果已回写 releaseId={} passed={} score={}", releaseId, passed, score);
        } catch (Exception e) {
            // 已知缺口：本地已判定但发布侧没收到。先喊出来，别静默。
            log.error("★ 门禁已判定但回写发布失败，需要人工处理 releaseId={} passed={} err={}",
                    releaseId, passed, e.getMessage());
        }
        return true;
    }

    /** 扫描器：把"卡住"的任务重投（执行器不可达 / 提交途中进程挂了）。 */
    @Scheduled(fixedDelay = 3000)
    public void retryStale() {
        // QUEUED 5 秒没动 = 没提交成功；RUNNING 300 秒没动 = 提交成功了但没回调（真评测要 1~2 分钟）
        List<Map<String, Object>> stale = repo.findStale(MAX_ATTEMPTS, 5, 300);
        for (Map<String, Object> row : stale) {
            long jobId = ((Number) row.get("id")).longValue();
            long releaseId = ((Number) row.get("release_id")).longValue();
            try {
                // 重新置为 QUEUED，再走一次提交（执行器按 jobId 幂等，重复提交不会重跑）
                repo.markQueuedAgain(jobId);
                dispatch(jobId, releaseId);
            } catch (Exception e) {
                log.warn("重投失败 jobId={} err={}", jobId, e.getMessage());
            }
        }
    }
}
