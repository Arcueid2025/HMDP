# 黑马点评 HMDP

黑马点评是一个基于 Spring Boot 的本地生活服务后端项目，模拟大众点评类应用的核心业务场景，覆盖用户登录、商户浏览、探店笔记、关注关系、优惠券秒杀、签到统计、附近商户搜索等功能。项目重点围绕 Redis 在高并发和高频访问场景下的应用展开，包含缓存、分布式锁、消息队列、位图、GEO 空间索引等实践。

## 技术栈

- Java 8
- Spring Boot 2.3.12.RELEASE
- Spring MVC
- MyBatis-Plus
- MySQL 8
- Redis
- Spring Data Redis
- Redisson
- Lua 脚本
- Lombok
- Hutool

## 核心功能

### 用户与登录

- 手机号验证码登录
- 登录 token 存储到 Redis
- 拦截器校验登录状态
- ThreadLocal 保存当前登录用户
- Redis token 自动续期

### 商户与缓存

- 商户详情查询
- 商户分类查询
- 商户信息缓存到 Redis
- 缓存穿透处理
- 逻辑过期缓存方案实践
- 商户更新后删除缓存，保证数据一致性

### 附近商户

- 使用 Redis GEO 存储商户经纬度
- 按商户类型查询附近店铺
- 根据用户坐标计算距离
- 支持按距离升序返回店铺列表

注意：当前代码使用 `GEOSEARCH`，Redis 服务端需要 6.2 或更高版本。

### 优惠券秒杀

- Redis 预扣库存
- Lua 脚本保证库存判断和一人一单判断的原子性
- Redis Stream 异步下单
- 后台线程消费订单消息
- Pending List 异常消息兜底处理
- Redisson 分布式锁防止同一用户重复下单
- MySQL 乐观扣减库存

### 探店笔记

- 发布探店笔记
- 查询热门笔记
- 查询笔记详情
- 点赞与取消点赞
- 使用 Redis ZSet 记录点赞用户
- 查询前 5 名点赞用户

### 关注与 Feed 流

- 关注和取消关注用户
- 查询是否已关注
- 查询共同关注
- 发布笔记后推送给粉丝
- 使用 Redis ZSet 实现收件箱
- 支持滚动分页查询关注用户动态

### 签到

- 使用 Redis Bitmap 记录每日签到
- 支持用户签到
- 支持统计当月连续签到天数
- 以 `sign:{userId}:yyyyMM` 作为签到 key

## 项目结构

```text
src/main/java/com/hmdp
├── config        配置类，包括 MVC、MyBatis-Plus、Redisson、异常处理
├── controller    接口层
├── dto           接口传输对象
├── entity        数据库实体
├── mapper        MyBatis-Plus Mapper
├── service       业务接口与实现
└── utils         Redis 常量、锁、ID 生成器、拦截器等工具类
```

```text
src/main/resources
├── application.yaml   项目配置
├── db/hmdp.sql        数据库初始化脚本
├── mapper             MyBatis XML
├── seckill.lua        秒杀 Lua 脚本
└── unlock.lua         分布式锁释放脚本
```

## 运行依赖

启动项目前需要准备：

- MySQL 数据库，并导入 `src/main/resources/db/hmdp.sql`
- Redis 服务
- 如果使用附近商户的 `GEOSEARCH` 功能，Redis 版本需要 6.2+
- 如使用秒杀异步下单，需要确保 Redis Stream 消费组存在

消费组可通过 Redis 命令创建：

```redis
XGROUP CREATE stream.orders g1 0 MKSTREAM
```

## 运行方式

修改 `src/main/resources/application.yaml` 中的 MySQL 和 Redis 连接配置后，执行：

```bash
mvn spring-boot:run
```

默认服务端口：

```text
8081
```

## 项目亮点

- 用 Redis 缓存降低数据库访问压力
- 用 Bitmap 高效记录签到状态
- 用 ZSet 实现点赞排行和 Feed 滚动分页
- 用 GEO 实现附近商户查询
- 用 Lua 脚本保证秒杀核心逻辑原子性
- 用 Redis Stream 解耦秒杀请求和订单落库
- 用 Redisson 分布式锁控制并发下的一人一单
- 用全局拦截器实现 token 校验和用户上下文传递

## 当前状态

项目核心业务链路已经基本完成，适合作为 Redis 实战学习项目。当前仍有一些可以继续完善的点：

- 统一修复源码中的中文乱码注释和提示信息
- 补充登出接口，删除 Redis 中的登录 token
- 在启动时自动创建 Redis Stream 消费组
- 补充接口测试和并发压测脚本
- 优化秒杀后台线程中的代理对象获取方式
- 根据部署环境确认 Redis 版本，避免 `GEOSEARCH` 命令不兼容

