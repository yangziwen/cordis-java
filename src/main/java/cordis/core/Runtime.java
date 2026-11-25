package cordis.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 对应 packages/core/src/registry.ts 的 Component.Runtime：
 * 一个组件的运行时登记信息（“户籍档案”）。
 *
 * 组件第一次被 use() 时在 Registry 中创建，保存其名字、回调与全部 fiber
 * 实例；当所有 fiber 都销毁时，Registry 会把档案一并注销。
 *
 * 可见性：包内协作类（Registry / Fiber），字段不做 public 暴露。
 */
public class Runtime {

    final String name;

    final Component callback;

    final List<Fiber> fibers = new ArrayList<>();

    Runtime(String name, Component callback) {
        this.name = name;
        this.callback = callback;
    }

}
