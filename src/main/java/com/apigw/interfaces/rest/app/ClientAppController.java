package com.apigw.interfaces.rest.app;

import com.apigw.application.app.ClientAppService;
import com.apigw.common.Result;
import com.apigw.common.exception.BizException;
import com.apigw.domain.app.ClientApp;
import com.apigw.infrastructure.store.dto.PageResult;
import com.apigw.interfaces.rest.app.vo.AppCreatedVO;
import com.apigw.interfaces.rest.app.vo.AppRowVO;
import com.apigw.interfaces.rest.app.vo.AppVO;
import com.apigw.interfaces.rest.app.vo.OriginIpVO;
import com.apigw.interfaces.rest.app.vo.OriginListVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 第三方接入管理接口（用户接口层）。只做协议适配与时间解析，业务在应用/领域层。
 *
 * 仅在 {@code apigw.app-auth.enabled=true} 时被组件扫描注册（类上的条件），
 * 关闭开关时控制器与其依赖的 Service/仓储一个都不进容器。
 *
 *   POST   /api/gateway/apps                       新建应用（响应里一次性回显明文密钥）
 *   GET    /api/gateway/apps/{appNo}               应用详情（不含任何密钥字段）
 *   GET    /api/gateway/apps?pageNum=&pageSize=&keyword=  分页列表（每行带来路条数）
 *   PUT    /api/gateway/apps/{appNo}/disable       停用（幂等）
 *   PUT    /api/gateway/apps/{appNo}/enable        启用（幂等）
 *   GET    /api/gateway/apps/{appNo}/origins       一次看清来路名单
 *   POST   /api/gateway/apps/{appNo}/origins       加入一条来路（幂等）
 *   DELETE /api/gateway/apps/{appNo}/origins?ip=   删除一条来路（幂等；ip 走 query 兼容 IPv6）
 *
 * 整个控制器的 JDBC 工作都在 boundedElastic 上，不占 Netty 事件循环。
 */
@RestController
@ConditionalOnProperty(prefix = "apigw.app-auth", name = "enabled", havingValue = "true")
@RequestMapping("/api/gateway/apps")
public class ClientAppController {

    private static final DateTimeFormatter LOCAL_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String SECRET_NOTICE =
            "请立即妥善保存该密钥：网关只存储不可逆散列，无法再次查看明文；遗失后请联系网关侧重新签发。";

    private final ClientAppService service;
    private final Clock clock;

    public ClientAppController(ClientAppService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public Mono<Result<AppCreatedVO>> create(
            @RequestBody com.apigw.interfaces.rest.app.vo.AppCreateVO body,
            @RequestHeader(value = "X-Created-By", required = false) String createdBy) {
        return Mono.fromCallable(() -> {
                    Instant expires = parseExpiry(body.secretExpiresAt());
                    // 创建人暂用约定请求头透传（管理接口未鉴权是已知边界，接上管理侧身份后替换成登录用户）
                    return service.create(body.appNo(), body.appName(), expires, body.enabled(),
                            body.contact(), body.remark(), createdBy);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(created -> new AppCreatedVO(toAppVO(created.app()), created.plainSecret(), SECRET_NOTICE))
                .map(Result::ok);
    }

    @GetMapping("/{appNo}")
    public Mono<Result<AppVO>> detail(@PathVariable String appNo) {
        return Mono.fromCallable(() -> service.detail(appNo))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toAppVO)
                .map(Result::ok);
    }

    @GetMapping
    public Mono<Result<PageResult<AppRowVO>>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        Instant now = Instant.now(clock);
        return Mono.fromCallable(() -> service.page(pageNum, pageSize, keyword))
                .subscribeOn(Schedulers.boundedElastic())
                .map(page -> new PageResult<>(
                        page.content().stream().map(row -> AppRowVO.of(row, now)).toList(),
                        page.total(), page.pageNum(), page.pageSize()))
                .map(Result::ok);
    }

    @PutMapping("/{appNo}/disable")
    public Mono<Result<Void>> disable(@PathVariable String appNo) {
        return Mono.fromRunnable(() -> service.disable(appNo))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Result.ok());
    }

    @PutMapping("/{appNo}/enable")
    public Mono<Result<Void>> enable(@PathVariable String appNo) {
        return Mono.fromRunnable(() -> service.enable(appNo))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Result.ok());
    }

    @GetMapping("/{appNo}/origins")
    public Mono<Result<OriginListVO>> origins(@PathVariable String appNo) {
        return Mono.fromCallable(() -> service.listOrigins(appNo))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ips -> OriginListVO.of(appNo, ips))
                .map(Result::ok);
    }

    @PostMapping("/{appNo}/origins")
    public Mono<Result<OriginListVO>> addOrigin(@PathVariable String appNo,
                                                @RequestBody OriginIpVO body) {
        return Mono.fromCallable(() -> {
                    if (body == null || body.ip() == null || body.ip().isBlank()) {
                        throw new BizException("ip 不能为空");
                    }
                    service.addOrigin(appNo, body.ip());
                    return service.listOrigins(appNo);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ips -> OriginListVO.of(appNo, ips))
                .map(Result::ok);
    }

    @DeleteMapping("/{appNo}/origins")
    public Mono<Result<OriginListVO>> removeOrigin(@PathVariable String appNo,
                                                   @RequestParam String ip) {
        return Mono.fromCallable(() -> {
                    service.removeOrigin(appNo, ip);
                    return service.listOrigins(appNo);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ips -> OriginListVO.of(appNo, ips))
                .map(Result::ok);
    }

    private AppVO toAppVO(ClientApp app) {
        Instant now = Instant.now(clock);
        boolean expired = app.getSecretExpiresAt() != null
                && !now.isBefore(app.getSecretExpiresAt());
        return new AppVO(
                app.getId(),
                app.getAppNo(),
                app.getAppName(),
                app.getSecretExpiresAt(),
                app.getSecretExpiresAt() == null,
                expired,
                app.getEnabled(),
                app.getContact(),
                app.getRemark(),
                app.getCreatedBy(),
                app.getCreatedAt(),
                app.getOrigins().size());
    }

    /** 有效期解析：空=长期有效；ISO-8601，带 Z/偏移按带的解释，不带偏移按 UTC（与流水时间口径一致）。 */
    private static Instant parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value, LOCAL_FORMAT).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new BizException("密钥有效期应为 ISO-8601，如 2027-09-26T10:00:00Z");
            }
        }
    }
}
