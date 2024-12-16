# 黑马点评

项目中 `Redis` 用途

1. 代替 `Session` ，解决集群共享问题（数据共享，内存存储，键值对存储）
   - 选择合适的数据结构（String、Hash 等）
   - 选择合适的 key（电话号码，token）
   - 设置合适的过期时间（如验证码设置为5分钟）
   - 选择合适的存储粒度（只保存业务需要的信息）

## 一、基于 Session 与 Redis 的短信登录

![](img/login_1.png)

### 1.1 发送短信验证码

`controller/UserController.sendCode` -> `service/impl/UserServiceImpl.sendCode`

课程的问题：黑马没有将电话号码保存，可能会导致用户使用一个号码接受验证码，使用另一个号码登录

### 1.2 短信验证码登录与注册

`controller/UserController.login` -> `service/impl/UserServiceImpl.login`

`UserServiceImpl.login` 中用到的 `com.baomidou.mybatisplus.extension.service.IService.query`
与 `UserServiceImpl.signUp` 中用到的 `com.baomidou.mybatisplus.extension.service.IService.save`
都是 `MyBatis` 的功能，可以帮助我们更方便地使用数据库

### 1.3 登录校验

`com.hmdp.utils.LoginInterceptor`

继承自`org.springframework.web.servlet.HandlerInterceptor`，主要实现三个方法：

 - `preHandle` `Controller` 执行之前
 - `postHandle` `Controller` 执行之后
 - `afterCompletion` 视图渲染之后，返回之前

使用拦截器拦截用户请求进行登录校验，并将用户信息保存到 `ThreadLocal` 中方便后续使用。

为了使拦截器生效，我们还需要配置拦截器：

`com.hmdp.config.MVCConfig` 注册拦截器并排除不需要进行用户验证的功能。

`com.hmdp.controller.UserController.me` 就可以从 `Session` 中获取用户信息（`UserDTO`）并返回

### 1.4 Session 的集群共享问题

`Session` 是单个 `Tomcat` 服务器独有的内存空间，当我们不同的请求被 `Nginx` 分流到不同的 `Tomcat` 中时，
不同的 `Tomcat` 服务器不能共享 `Session` 资源，导致需要用户重复登录等异常行为。因此我们需要一个代替方案，
这个方案需要满足：数据共享，内存存储，键值对存储的特点。

因此可以使用 `Redis` 代替 `Session` 解决这个问题

### 1.5 基于 Redis 实现共享 Session 登录

![](img/login_2.png)

由于 `Session` 是每个浏览器发送请求时独有的，不会互相干扰，而 `Redis` 将所有的浏览器的信息都保存在同一个 `Redis` 服务器中，
因此需要谨慎选择 `key` ，如进行验证码操作时 `code` 不可以作为一个 `key` ，因为不能区分这个验证码对应的是哪个用户。
保存用户信息时我们可以使用随机的 `token` （不用手机号码，满足安全性），使用 `hash` 结构来存储用户信息（也是 `k-v` 结构）。

#### 1.5.1 修改发送短信验证码流程

`com.hmdp.service.impl.UserServiceImpl.sendCode`

![](img/login_3.png)

我们不把验证码 `code` 保存到 `Session` 中，而是保存到 `Redis` 中。

    stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

其中 `LOGIN_CODE_KEY` 和 `LOGIN_CODE_TTL` 来自 `com.hmdp.utils.RedisConstant`。

#### 1.5.2 修改短信验证码登录与注册

`com.hmdp.service.impl.UserServiceImpl.login`

方法同上。

我们将过期时间设置为用户不操作的30分钟以后，因此需要在拦截器中实时更新过期时间。

`com.hmdp.utils.LoginInterceptor`

注意，`com.hmdp.utils.LoginInterceptor` 没有添加 `Spring` 注解，因此不受 `Spring` 控制，因此我们不可以使用 `@Resource` 或者
`@Autowired` 之类的注解注入 `SpringRedisTemplate`。我们需要创建构造函数并且传入 `SpringRedisTemplate`。由于我们在
`com.hmdp.config.MVCConfig`中使用该拦截器，而 `com.hmdp.config.MVCConfig` 是一个 `Spring` 管理的类，因此可以在
`com.hmdp.config.MVCConfig` 中注入。

### 1.6 登录拦截器优化

由于现有的拦截器只对需要登录才能访问的页面进行拦截，因此如果用户一直访问无需登陆就可以查看的页面，也会导致登录失效。因此我们可以再增加一个拦截器，
拦截所有的请求，防止登录失效。

![](img/login_4.png)

`com.hmdp.utils.RefreshTokenInterceptor` 用于刷新 token

`com.hmdp.utils.LoginInterceptor` 用于拦截未登录用户

## 二、商户查询缓存

### 2.1 Redis 缓存

为 `com.hmdp.controller.ShopController.queryShopById` `com.hmdp.controller.ShopTypeController.queryTypeList`
添加 Redis 缓存。

### 2.2 Redis 缓存更新

为了解决商铺缓存与数据库一致性问题，我们首先可以添加查询商铺的缓存失效时间。
（`com.hmdp.service.impl.ShopServiceImpl.queryById`）

接下来是更新业务，`com.hmdp.controller.ShopController.updateShop`
其实就是两步，先更新数据库，然后删除缓存（为什么不能先删除缓存然后更新数据库？因为删除缓存，如果数据库更新操作还没有完成就有查询请求，
会返回未更新的信息并写入缓存，该缓存失效前缓存与数据库不一致）。

### 2.3 缓存穿透

请求无效 key 导致 Redis 中无法找到该 key，访问数据库。

- 缓存空对象

`com.hmdp.service.impl.ShopServiceImpl.queryById`

![](img/shop_1.png)

- 布隆过滤器

使用哈希计算数据库中所有信息，并按哈希位保存在过滤器中。当收到请求时计算请求哈希，如果命中了布隆过滤器中没有的位则直接返回，
如果命中了已有的位则放行。因此布隆过滤器不能完全过滤缓存穿透请求。

### 2.4 缓存雪崩

短时间内大量缓存失效或者 Redis 宕机，导致大量请求同时到达数据库。

- Redis TTL 增加随机值
- 使用Redis 集群

### 2.5 缓存击穿

热点 key 过期导致大量热点请求命中数据库。

#### 2.5.1 互斥锁

缓存重建时使用互斥锁，防止大量的请求同时访问数据库进行缓存重建。

![](img/shop_2.png)

`com.hmdp.service.impl.ShopServiceImpl.tryLock` `com.hmdp.service.impl.ShopServiceImpl.unlock`
自定义锁的获取与释放
锁通过 Redis 实现

`com.hmdp.service.impl.ShopServiceImpl.queryById` 实现互斥锁解决缓存穿透的问题并移动到
`com.hmdp.service.impl.ShopServiceImpl.queryShopById`。原方法内注释保留了原实现。

#### 2.5.2 逻辑过期

不设置过期时间，在字段中加入一个逻辑过期时间，当请求该数据时已经到达逻辑过期时间，开新线程进行更新，而该线程直接返回旧数据。
