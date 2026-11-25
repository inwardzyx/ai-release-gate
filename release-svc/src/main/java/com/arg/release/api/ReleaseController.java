package com.arg.release.api;

import com.arg.release.client.ConfigClient;
import com.arg.release.domain.Release;
import com.arg.release.gray.StableHash;
import com.arg.release.repo.ReleaseRepository;
import com.arg.release.web.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 发布接口。
 *
 * <p>★ 状态变更一律要求客户端带 {@code rev}（乐观锁）：
 * 两个值班同时点"回滚"，没有 rev 就会出现"两个都成功、指针最后落在谁手里看运气"。
 * 带 rev 之后，后到的那个拿到 409 —— 这是**正确**的行为，不是故障。
 */
@RestController
@RequestMapping("/api")
public class ReleaseController {

    private static final int MAX_LIMIT = 100;

    private final ReleaseRepository repo;
    private final ConfigClient configClient;

    public ReleaseController(ReleaseRepository repo, ConfigClient configClient) {
        this.repo = repo;
        this.configClient = configClient;
    }

    // ---------------------------------------------------------------- 建发布

    public record CreateReleaseRequest(
            @NotBlank String env,
            @NotBlank String promptKey,
            @NotNull @Min(1) Integer version,
            @NotBlank String operator) {
    }

    @PostMapping("/releases")
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @Valid @RequestBody CreateReleaseRequest req) {

        // ① 跨服务校验：版本必须真的存在（fail closed）
        configClient.requireVersion(req.promptKey(), req.version());

        // ② 建 PENDING 发布（prev_release_id 记下"发布前生效的那个"，回滚靠它）
        ReleaseRepository.CreateOutcome out =
                repo.create(req.env(), req.promptKey(), req.version(), req.operator(), idemKey);
        if (out.idempotentHit()) {
            return ResponseEntity.ok(out.release());
        }
        return ResponseEntity.created(URI.create("/api/releases/" + out.release().id()))
                .body(out.release());
    }

    // ---------------------------------------------------------------- 状态迁移

    public record ChangeRequest(@NotNull Integer rev, @Min(1) @Max(100) Integer rolloutPct) {
    }

    @PostMapping("/releases/{id}/canary")
    public Release canary(@PathVariable long id, @Valid @RequestBody ChangeRequest req) {
        int pct = req.rolloutPct() == null ? 5 : req.rolloutPct();
        return repo.canary(id, pct, req.rev());
    }

    @PostMapping("/releases/{id}/promote")
    public Release promote(@PathVariable long id, @Valid @RequestBody ChangeRequest req) {
        return repo.promote(id, req.rev());
    }

    @PostMapping("/releases/{id}/rollback")
    public Release rollback(@PathVariable long id, @Valid @RequestBody ChangeRequest req) {
        return repo.rollback(id, req.rev());
    }

    // ---------------------------------------------------------------- 查询

    @GetMapping("/releases/{id}")
    public Release get(@PathVariable long id) {
        return repo.findById(id).orElseThrow(() -> BizException.notFound("发布不存在: id=" + id));
    }

    @GetMapping("/releases")
    public List<Release> list(@RequestParam String env,
                              @RequestParam String promptKey,
                              @RequestParam(defaultValue = "20") int limit) {
        return repo.list(env, promptKey, Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    // ---------------------------------------------------------------- 灰度决策

    /**
     * 灰度决策：**应用在每次请求里调它**（所以它是本服务唯一的高频接口）。
     *
     * <p>返回"这个租户这次该看哪一版"，而不是返回整个发布记录 —— 调用方只关心结论。
     * 同时把 {@code bucket} 和 {@code rolloutPct} 一起给出来：出问题时能一眼看出
     * "是这个租户的桶号偏了，还是比例设错了"，不用反推哈希。
     */
    @GetMapping("/gray")
    public GrayDecision gray(@RequestParam String env,
                             @RequestParam String promptKey,
                             @RequestParam String tenantId) {
        Release cur = repo.current(env, promptKey)
                .orElseThrow(() -> BizException.notFound("这个 key 当前没有生效版本: " + env + "/" + promptKey));
        int bucket = StableHash.bucket(tenantId);
        boolean hitNew = bucket < cur.rolloutPct();

        Integer fallbackVersion = null;
        if (cur.prevReleaseId() != null) {
            fallbackVersion = repo.findById(cur.prevReleaseId()).map(Release::version).orElse(null);
        }
        return new GrayDecision(env, promptKey, tenantId, bucket, cur.rolloutPct(),
                cur.id(), cur.version(), hitNew, fallbackVersion);
    }

    public record GrayDecision(String env, String promptKey, String tenantId, int bucket,
                               int rolloutPct, long releaseId, int version, boolean hitNew,
                               Integer fallbackVersion) {
    }

    /** 只证进程活着（不查库、不查上游）。 */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("service", "release-svc", "status", "ok");
    }
}
