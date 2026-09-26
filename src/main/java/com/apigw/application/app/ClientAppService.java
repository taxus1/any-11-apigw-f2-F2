package com.apigw.application.app;

import com.apigw.common.exception.BizException;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.app.ClientApp;
import com.apigw.domain.app.DuplicateAppNoException;
import com.apigw.infrastructure.store.dto.PageResult;
import com.apigw.proxy.auth.AppCredentialChangedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 第三方应用接入的应用服务：编排用例、守住事务/一致性边界、决定密钥怎么发。
 *
 * 密钥发放约定（重点）：
 * - 新建应用时由网关用 {@link SecureRandom} 生成一把高熵随机密钥，散列入库，
 *   **明文只在创建结果里返回这一次**；列表、详情都不含散列更不含明文，
 *   系统里没有任何「再看一眼原密钥」的口子。对方丢了只能联系网关侧重新发（后续可加换发接口）。
 *
 * 生效时机：任何写操作的事务提交之后发 {@link AppCredentialChangedEvent}，
 * 本实例鉴权快照立即重载，停用/改名单对**下一笔新请求**即时生效（没有宽限期）。
 *
 * JDBC 阻塞：本服务方法都被接口层切到 boundedElastic 调用，这里直接写同步逻辑。
 */
public class ClientAppService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final AppCredentialRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ClientAppService(AppCredentialRepository repository,
                            ApplicationEventPublisher events,
                            Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    /** 创建结果：除了应用本身，还带一次性明文密钥（除此处外系统不再保留/展示它）。 */
    public record CreatedApp(ClientApp app, String plainSecret) {
    }

    /**
     * 新建应用。
     *
     * @param expiresAt 密钥有效期截止时刻；null/空 = 长期有效，非空必须是未来时刻
     */
    public CreatedApp create(String appNo, String appName, Instant expiresAt, Integer enabled,
                             String contact, String remark, String createdBy) {
        String plainSecret = newSecret();
        ClientApp app = ClientApp.create(appNo, appName, plainSecret, expiresAt, enabled,
                contact, remark, createdBy, clock);
        try {
            repository.insert(app);
        } catch (DuplicateAppNoException e) {
            // 编号重复：唯一索引原子拒绝，翻译成业务失败消息（不暴露更多内部细节）
            throw new BizException(e.getMessage());
        }
        events.publishEvent(AppCredentialChangedEvent.created(app.getAppNo()));
        return new CreatedApp(app, plainSecret);
    }

    /** 应用详情（含来路名单）；密钥散列不返回。 */
    public ClientApp detail(String appNo) {
        return repository.findByAppNo(appNo)
                .orElseThrow(() -> new BizException(404, "应用不存在：" + appNo));
    }

    /**
     * 分页列表：编号/名称忽略大小写模糊匹配，按编号稳定排序。
     * count 与取数在仓储的同一只读事务里，分页数字严格对得上；
     * 每行带 originCount，前端不用逐行再查。
     */
    public PageResult<AppCredentialRepository.AppRow> page(int pageNum, int pageSize, String keyword) {
        int pn = pageNum < 1 ? 1 : pageNum;
        int ps = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String kw = keyword == null ? null : keyword.trim();
        if (kw != null && kw.isEmpty()) {
            kw = null;
        }
        long offset = (long) (pn - 1) * ps;
        AppCredentialRepository.AppPage result = repository.page(kw, offset, ps);
        return new PageResult<>(result.content(), result.total(), pn, ps);
    }

    /** 停用：不存在报 404；已经是停用态再调一次照样成功（幂等）。 */
    public void disable(String appNo) {
        setEnabled(appNo, 0);
    }

    /** 启用：不存在报 404；已经是启用态再调一次照样成功（幂等）。 */
    public void enable(String appNo) {
        setEnabled(appNo, 1);
    }

    private void setEnabled(String appNo, int target) {
        int affected = repository.updateEnabled(appNo, target);
        if (affected == 0) {
            throw new BizException(404, "应用不存在：" + appNo);
        }
        events.publishEvent(AppCredentialChangedEvent.enabled(appNo, target));
    }

    /** 列出某应用的全部来路地址（规范化、稳定排序）；应用不存在报 404。 */
    public List<String> listOrigins(String appNo) {
        ClientApp app = detail(appNo);
        return app.sortedOrigins();
    }

    /**
     * 加入一条来路：地址必须是合法 IP 字面量（不收主机名/网段），归一再存。
     * 重复加同一条幂等成功。返回是否本次新加（false=原来就在名单里）。
     */
    public boolean addOrigin(String appNo, String rawIp) {
        ClientApp app = detail(appNo);
        // 聚合负责合法性校验/归一/上限；只有真的新增了才写库、发变更事件
        ClientApp.OriginChange change = app.addOrigin(rawIp);
        if (change.changed()) {
            repository.addOrigin(appNo, change.canonicalIp(), Instant.now(clock));
            events.publishEvent(AppCredentialChangedEvent.originChanged(appNo, "add"));
        }
        return change.changed();
    }

    /** 删除一条来路：本来就没有也算成功（幂等）。应用不存在报 404。 */
    public boolean removeOrigin(String appNo, String rawIp) {
        ClientApp app = detail(appNo);
        ClientApp.OriginChange change = app.removeOrigin(rawIp);
        if (change.changed()) {
            repository.removeOrigin(appNo, change.canonicalIp());
            events.publishEvent(AppCredentialChangedEvent.originChanged(appNo, "remove"));
        }
        return change.changed();
    }

    /** 生成一把网关签发的高熵密钥：32 字节随机数，URL 安全 Base64（无填充、无歧义字符）。 */
    private static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }
}
