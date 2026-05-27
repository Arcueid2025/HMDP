package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  鏈嶅姟瀹炵幇绫?
 * </p>
 *
 * @author 铏庡摜
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private  static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

        public class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    //1.鑾峰彇娑堟伅闃熷垪閲岀殑璁㈠崟淇℃伅
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    //2.鍒ゆ柇鑾峰彇娑堟伅鏄惁鎴愬姛
                    if(list == null || list.isEmpty()){
                        //2.1濡傛灉鑾峰彇澶辫触锛岃鏄庢病鏈夋秷鎭紝缁х画涓嬩竴娆″惊鐜?
                        continue;
                    }

                    //3.瑙ｆ瀽娑堟伅涓殑璁㈠崟淇℃伅
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);


                    //3.濡傛灉鑾峰彇鎴愬姛锛屽彲浠ヤ笅鍗?
                    handleVouchrOrder(voucherOrder);

                    //4.ACK纭

                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("澶勭悊璁㈠崟寮傚父", e);
                    handlePendingList();
                }
            }

        }

            private void handlePendingList() {
                while (true) {
                    try {
                        //1.鑾峰彇pending-list閲岀殑璁㈠崟淇℃伅
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                                Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1),
                                StreamOffset.create(queueName, ReadOffset.from("0"))
                        );

                        //2.鍒ゆ柇鑾峰彇娑堟伅鏄惁鎴愬姛
                        if(list == null || list.isEmpty()){
                            //2.1濡傛灉鑾峰彇澶辫触锛岃鏄巔ending-list娌℃湁娑堟伅,缁撴潫寰幆
                            break;
                        }

                        //3.瑙ｆ瀽娑堟伅涓殑璁㈠崟淇℃伅
                        MapRecord<String, Object, Object> record = list.get(0);
                        Map<Object, Object> values = record.getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);


                        //3.濡傛灉鑾峰彇鎴愬姛锛屽彲浠ヤ笅鍗?
                        handleVouchrOrder(voucherOrder);

                        //4.ACK纭

                        stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                    }catch (Exception e) {
                        log.error("澶勭悊pending-list璁㈠崟寮傚父", e);
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ex) {
                            throw new RuntimeException(ex);
                        }

                    }
            }
        }
    }
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    public class VoucherOrderHandler implements Runnable {
//
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //1.鑾峰彇闃熷垪閲岀殑璁㈠崟淇℃伅
//                    VoucherOrder voucherOrder = orderTasks.take();
//
//                    //2.鍒涘缓璁㈠崟
//                    handleVouchrOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("澶勭悊璁㈠崟寮傚父", e);
//                }
//            }
//
//        }
//    }

    private void handleVouchrOrder(VoucherOrder voucherOrder) {
        //1.鑾峰彇鐢ㄦ埛
        Long userId = voucherOrder.getUserId();
        //2.鍒涘缓閿佸璞°€?
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //3.鑾峰彇閿?
        boolean isLock = lock.tryLock();

        //4.鍒ゆ柇鏄惁鑾峰彇閿佹垚鍔?
        if (!isLock) {
            //鑾峰彇閿佸け璐ワ紝杩斿洖閿欒淇℃伅鎴栭噸璇?
            log.error("不允许重复下单");
            return;

        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //閲婃斁閿?
            lock.unlock();
        }
    }

    private  IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //鑾峰彇鐢ㄦ埛
        Long userId = UserHolder.getUser().getId();
        //鑾峰彇璁㈠崟ID
        Long orderId = redisIdWorker.nextId("order");
        //1.鎵цlua鑴氭湰
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );

        //2.鍒ゆ柇缁撴灉鏄惁涓?
        int r = result.intValue();
        if(r != 0){
            //2.1.涓嶄负0锛屾病鏈夎喘涔拌祫鏍?
            return Result.fail(r == 1 ? "搴撳瓨涓嶈冻" : "涓嶈兘閲嶅涓嬪崟");

        }



        //鑾峰彇浠ｇ悊瀵硅薄(浜嬪姟)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3.杩斿洖璁㈠崟ID
        return Result.ok(orderId);

    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        //1.鎵цlua鑴氭湰
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//
//        //2.鍒ゆ柇缁撴灉鏄惁涓?
//        int r = result.intValue();
//        if(r != 0){
//            //2.1.涓嶄负0锛屾病鏈夎喘涔拌祫鏍?
//            return Result.fail(r == 1 ? "搴撳瓨涓嶈冻" : "涓嶈兘閲嶅涓嬪崟");
//
//        }
//
//
//        //2.2.涓?锛屾湁璐拱璧勬牸锛屾妸涓嬪崟淇℃伅淇濆瓨鍒伴樆濉為槦鍒椾腑
//
//        VoucherOrder voucherOrder = new VoucherOrder();
//
//        //2.3.璁㈠崟ID
//        Long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//        //2.4.鐢ㄦ埛ID
//
//        voucherOrder.setUserId(userId);
//
//        //2.5.浠ｉ噾鍒窱D
//        voucherOrder.setVoucherId(voucherId);
//
//        //2.6鏀惧叆闃诲闃熷垪
//        orderTasks.add(voucherOrder);
//
//        //3.鑾峰彇浠ｇ悊瀵硅薄
//        //鑾峰彇浠ｇ悊瀵硅薄(浜嬪姟)
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        //3.杩斿洖璁㈠崟ID
//        return Result.ok(orderId);
//
//    }

    @Override

//    public Result seckillVoucher(Long voucherId) {
//        //1.鏌ヨ浼樻儬鍒?
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.鍒ゆ柇绉掓潃鏄惁寮€濮?
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //灏氭湭寮€濮?
//            return Result.fail("绉掓潃灏氭湭寮€濮?");
//        }
//        //3.鍒ゆ柇绉掓潃鏄惁缁撴潫
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //宸茬粡缁撴潫
//            return Result.fail("绉掓潃宸茬粡缁撴潫!");
//        }
//        //4.鍒ゆ柇搴撳瓨鏄惁鍏呰冻
//        if (voucher.getStock() < 1) {
//            //搴撳瓨涓嶈冻
//            return Result.fail("搴撳瓨涓嶈冻!");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        //鍒涘缓閿佸璞°€?
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        //鑾峰彇閿?
//        boolean isLock = lock.tryLock();
//        //鍒ゆ柇鏄惁鑾峰彇閿佹垚鍔?
//        if (!isLock) {
//            //鑾峰彇閿佸け璐ワ紝杩斿洖閿欒淇℃伅鎴栭噸璇?
//            return Result.fail("璇峰嬁閲嶅涓嬪崟!");
//
//        }
//
//        try {
//            //鑾峰彇浠ｇ悊瀵硅薄(浜嬪姟)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //閲婃斁閿?
//            lock.unlock();
//        }
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.涓€浜轰竴鍗?
        //5.1.鏌ヨ璁㈠崟
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            //5.2.鍒ゆ柇璁㈠崟鏄惁瀛樺湪
            if (count > 0) {
                //鐢ㄦ埛宸茬粡璐拱杩囦簡
                log.error("鐢ㄦ埛宸茬粡璐拱杩囦竴娆′簡!");
                return;
            }

            //6.鎵ｅ噺搴撳瓨
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                    .update();
            if (!success) {
                //鎵ｅ噺澶辫触
                log.error("搴撳瓨涓嶈冻!");
                return;
            }

            //7.鍒涘缓璁㈠崟
            save(voucherOrder);


    }
}
