# Backend Service Guidelines

本文件适用于 `services/` 下全部微服务，并与仓库根 `AGENTS.md` 共同生效。服务自己的嵌套 `AGENTS.md` 可补充更具体的领域规则。

## Java 与 Spring 约定

- 使用 4 空格缩进，并保持所在模块的现有代码风格。
- 使用构造器注入；优先 `@RequiredArgsConstructor`，不新增字段注入。
- 按业务边界组织 `controller`、`service`、`dto`、`client`、`messaging`、`repository` 等包，不为无关代码做跨模块重构。
- Controller 使用现有 `ApiResponse`、`PageResponse`；业务异常沿用 `BusinessException` 与 `GlobalExceptionHandler` 边界，授权约束沿用 `@PreAuthorize` 与共享资源服务器配置。
- 新代码优先复用所属服务已有模式；只有确认多个服务需要同一基础能力后，才考虑下沉到 `platform/`。

## 数据所有权与 Flyway

- 服务只能访问并维护自己的 Schema；禁止读取、写入或关联查询其他服务数据库。
- 持有数据库的服务在本模块 `src/main/resources/db/migration/` 维护 Flyway 迁移。
- Schema 变更必须新增版本化迁移，禁止修改已发布脚本或用手工改库替代迁移。
- 涉及数据迁移、回填、索引或依赖数据分布的性能变更时，核实相关结构及必要的数据分布，并按变更风险说明备份、兼容与回滚方案；无法完成的部署前验证应明确列出证据缺口。

## 消息与契约

- 跨服务同步调用通过 `apis/` 中的 Feign 契约，异步协作通过公共事件；不得用数据库耦合绕过契约。
- 业务状态与 `outbox_event` 在同一事务写入；消费者使用 `inbox_event` 或等价唯一约束实现幂等。
- DTO 和事件必须可序列化、可演进。新增或修改字段时检查调用方、实现方、生产者、消费者及历史消息兼容性。
- 修改共享 RabbitMQ 拓扑、重试、死信或 Outbox 状态机前，定位所有声明方并明确发布顺序；多代理协作时由主代理协调共享定义的写入，独立调查与验证可并行。

## 验证

- 优先验证目标模块及其依赖，例如：

```bash
./mvnw -pl services/exam-runtime-service -am test
```

- 将示例路径替换为实际模块；不要使用根聚合 POM 无目标地执行 `spring-boot:run`。
- Testcontainers 测试依赖 Docker；报告结果时区分环境失败、测试失败和未执行验证。
