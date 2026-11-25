package com.arg.config.api;

import com.arg.config.domain.ConfigVersion;
import com.arg.config.repo.ConfigVersionRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 配置版本接口。
 *
 * <p>★ 三个语义细节（都会被追问，写在代码里）：
 * <ul>
 *   <li><b>201 vs 200</b>：新建返回 201 + {@code Location}；命中幂等（同一 {@code Idempotency-Key}
 *       重复提交）返回 <b>200</b> + 同一个版本 —— 这不是"错误"，但也不能说"又建了一个"。</li>
 *   <li><b>幂等键用请求头</b>而不是 body 字段：它属于"传输层的重试语义"，
 *       不该混进业务字段（业务字段会被存下来、被审计、被前端编辑）。</li>
 *   <li><b>查询不做分页对象</b>：MVP 阶段一个 promptKey 的版本数是个位到几十，
 *       先返回数组；等真的需要分页再加 {@code page/pageSize}，不要提前抽象。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/config")
public class ConfigVersionController {

    /** 版本列表一次最多返回多少条：防止有人一次拉几万条把库拖慢。 */
    private static final int MAX_LIMIT = 100;

    private final ConfigVersionRepository repo;

    public ConfigVersionController(ConfigVersionRepository repo) {
        this.repo = repo;
    }

    @PostMapping("/versions")
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @Valid @RequestBody CreateVersionRequest req) {

        ConfigVersionRepository.CreateOutcome out =
                repo.create(req.promptKey(), req.payload(), req.createdBy(), idemKey);

        if (out.idempotentHit()) {
            // 命中幂等：同一个版本已存在，200 而不是 201
            return ResponseEntity.ok(out.version());
        }
        return ResponseEntity
                .created(URI.create("/api/config/versions/" + out.version().id()))
                .body(out.version());
    }

    @GetMapping("/versions")
    public List<ConfigVersion> list(@RequestParam String promptKey,
                                    @RequestParam(defaultValue = "20") int limit) {
        return repo.list(promptKey, Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    @GetMapping("/versions/latest")
    public ConfigVersion latest(@RequestParam String promptKey) {
        // 查不到就抛：错误体由 GlobalExceptionHandler 统一成 ApiError（只有一个出口）
        return repo.findLatest(promptKey).orElseThrow(() -> new VersionNotFoundException(promptKey));
    }

    /** 给编排系统与冒烟脚本用：不查库，只证进程活着（别把 DB 探活塞进 ping）。 */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("service", "config-svc", "status", "ok");
    }
}

