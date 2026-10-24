package com.lab.troubleshoot.fault.cpu;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import com.lab.troubleshoot.core.FaultState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景 07：序列化风暴 —— 「框架开销」型 CPU 高。
 *
 * <p>【病灶原型】接口返回大对象列表 + 高频调用（报表导出、批量查询），
 * Jackson 序列化/反序列化成为 CPU 大头。与场景 05/06 的区别：栈大部分落在
 * <b>序列化框架内部</b>（com.fasterxml.jackson.*），但业务帧仍在栈的中下部——
 * 教学点：这不是「框架的锅」，是业务选择了「把 5MB 对象反复序列化」的做法。</p>
 *
 * <p>本场景同时演示序列化+反序列化双向开销（内部 RPC 场景是「序列化 A 端 +
 * 反序列化 B 端」双倍 CPU），CPU 高但接口不报错、只是 RT 变长——「温水煮青蛙」型故障。</p>
 */
@Slf4j
@Component
public class JsonStormScenario extends AbstractFaultScenario {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 反序列化目标类型（泛型擦除下需要 TypeReference 持有完整类型） */
    private static final TypeReference<List<Map<String, Object>>> TYPE = new TypeReference<>() {
    };

    private volatile boolean running;
    private final AtomicLong ops = new AtomicLong();

    @Override
    public String id() { return "cpu-json-storm"; }

    @Override
    public String name() { return "序列化风暴：大对象反复 JSON 化"; }

    @Override
    public FaultCategory category() { return FaultCategory.CPU; }

    @Override
    public int order() { return 7; }

    @Override
    public String alertText() {
        return "【P1告警】troubleboot-lab CPU 持续 150%~210%, /api/report/export 接口 RT 从 800ms 涨至 4.5s, "
                + "无错误无 GC 异常, 出口带宽 +3 倍";
    }

    @Override
    public String symptom() {
        return "3 个线程持续序列化+反序列化 ~4MB 对象图。jstack 栈在 jackson 框架帧 —— "
                + "训练「框架栈反推业务代码」：栈往下翻找到业务调用行。对照 docs/07-序列化风暴.md。";
    }

    @Override
    public String docFile() { return "07-序列化风暴.md"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int workers = intParam(params, "workers", 3);
        int entities = intParam(params, "entities", 40_000); // 约 4MB 的对象图
        running = true;

        // 预构建大对象图（模拟「报表全量数据」）：4 万条记录 × 每条 ~100B
        List<Map<String, Object>> payload = buildPayload(entities);
        int payloadMb = estimateJsonMb(payload);
        log.info("[cpu-json-storm] payload 就绪: {} 条实体, 序列化后约 {} MB", entities, payloadMb);

        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "json-burner");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                while (running) {
                    try {
                        // 序列化（HTTP 响应侧的开销）……
                        String json = MAPPER.writeValueAsString(payload);
                        // ……再反序列化（RPC/缓存读取侧的开销）：一进一出双倍 CPU
                        List<Map<String, Object>> back = MAPPER.readValue(json, TYPE);
                        if (back.size() != payload.size()) {
                            throw new IllegalStateException("不可能的大小不一致");
                        }
                        ops.incrementAndGet();
                    } catch (Exception e) {
                        log.error("[cpu-json-storm] 序列化异常", e);
                        break;
                    }
                }
            });
        }
        log.warn("[cpu-json-storm] {} 个序列化线程已启动。jstack {} 栈帧应落在 jackson —— 往下翻找业务行",
                workers, pid());
    }

    @Override
    public void doStop() {
        running = false;
        log.info("[cpu-json-storm] 风暴停止, 共完成 {} 轮序列化+反序列化", ops.get());
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "running", running,
                "serializeOps", ops.get(),
                "state", FaultState.RUNNING.getLabel()
        );
    }

    private static List<Map<String, Object>> buildPayload(int entities) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Map<String, Object>> list = new ArrayList<>(entities);
        for (int i = 0; i < entities; i++) {
            list.add(Map.of(
                    "id", i,
                    "sku", "SKU-" + rnd.nextInt(1_000_000),
                    "title", "商品标题示例文本-" + rnd.nextInt(100_000),
                    "price", rnd.nextInt(100_000) / 100.0,
                    "tags", List.of("tag" + rnd.nextInt(50), "tag" + rnd.nextInt(50))
            ));
        }
        return list;
    }

    private static int estimateJsonMb(List<Map<String, Object>> payload) {
        try {
            return MAPPER.writeValueAsBytes(payload).length >> 20;
        } catch (Exception e) {
            return -1;
        }
    }
}
